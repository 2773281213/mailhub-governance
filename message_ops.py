"""邮件动作的公共流程：策略闸门、本地删除、恢复、撤销、已读状态登记

删除邮件原本在三个入口各写了一遍（`app.batch_messages`、`app.clean_messages`、
`sync.auto_clean`）。它们共用同一个两步顺序：先由 `gate()` 过策略闸门，再由调用方
执行服务器动作，最后按服务器确认的 id 调用 `finish_removal()` 改本地状态。

`LOCAL_MODE.md` 第四节规则 8 要求「不可逆的服务器动作，服务器确认成功之后才更新
本地状态」。以前这只是三段代码的书写顺序；现在 `finish_removal(conn, "purge", ...)`
用签名强制它：不传 `server_confirmed` 就抛 `ValueError`，本地一行都不会动。

本模块不 import `sync` 和 `app`（避免循环依赖）：IMAP 服务器动作与已读状态同步的
触发都由调用方负责。这里只依赖 `policy`、`db` 和标准库。
"""
import json
import time

import db
import policy

# SQLite 一条语句最多 32766 个绑定参数（本项目 SQLite 3.49.1 实测：32766 个可以，
# 32767 个报 `too many SQL variables`）。几万封邮件放在一个 `id IN (...)` 里必然翻车，
# 所以按这个块大小逐块执行。
SQL_CHUNK = 500


def chunk_ids(ids: list[int]):
    """把 id 列表去重升序后按 SQL_CHUNK 分块，供 `id IN (...)` 逐块执行。

    去重升序与原来的单条语句结果一致：SQLite 对 `id IN (...)` 按主键升序返回
    每个匹配行，重复的 id 也只算一次。分块只把一条语句拆成几条，不改变事务边界，
    commit 仍由调用方负责。
    """
    unique = sorted(set(ids))
    for start in range(0, len(unique), SQL_CHUNK):
        yield unique[start:start + SQL_CHUNK]


def load_for_policy(conn, ids: list[int]) -> list[dict]:
    """只取策略引擎要看的列；回收站里的邮件不算。

    分块查询后按块顺序合并：块内按 id 升序、块与块之间也是升序，
    因此合并结果与单条 `id IN (...)` 查询相同（按 id 升序，重复 id 只返回一行）。
    """
    rows: list[dict] = []
    for chunk in chunk_ids(ids):
        ph = ",".join("?" * len(chunk))
        rows.extend(dict(r) for r in conn.execute(
            f"""SELECT id, category, bill_tier, has_attach, importance, sender_addr, unread, deleted_ts
                FROM messages WHERE deleted_ts=0 AND id IN ({ph})""", chunk).fetchall())
    return rows


def register_flag_push(conn, ids: list[int], flag: int) -> list[int]:
    """登记「待写回服务器的已读状态」，返回涉及的账户 id（去重、升序）。

    flag：1 = 下次同步要把这些邮件在服务器上标为已读（本地同时置已读），
    -1 = 标为未读（本地同时置未读）。调用方负责只传入状态确实发生变化的邮件，
    返回的账户 id 供调用方逐个触发同步。
    """
    if not ids:
        return []
    flag_unread, flag_push = (0, 1) if flag > 0 else (1, -1)
    for chunk in chunk_ids(ids):
        ph = ",".join("?" * len(chunk))
        conn.execute(f"UPDATE messages SET unread=?, flag_push=? WHERE id IN ({ph})",
                     [flag_unread, flag_push] + chunk)
    accounts: set[int] = set()
    for chunk in chunk_ids(ids):
        ph = ",".join("?" * len(chunk))
        accounts.update(int(r["account_id"]) for r in conn.execute(
            f"SELECT DISTINCT account_id FROM messages WHERE id IN ({ph})", chunk).fetchall())
    return sorted(accounts)


def gate(action: str, msgs: list[dict], *, actor: str, confirmed: bool = False,
         user_rule: bool = False, override_protection: bool = False
         ) -> tuple[list[dict], list[dict]]:
    """逐封过 policy.evaluate。

    返回 (放行的邮件行, 被拦截的列表)。被拦截的每一项是
    {"id": ..., "reason": ..., "protected": ...}，其中 protected 表示
    「这是个会让邮件离开收件箱的动作，而且这封邮件受保护」
    （action in policy.DESTRUCTIVE_ACTIONS and policy.is_protected(m)[0]）。
    两个列表都保持 msgs 的原有顺序。
    """
    allowed: list[dict] = []
    blocked: list[dict] = []
    for m in msgs:
        ok, _tier, reason = policy.evaluate(
            action, m, actor=actor, confirmed=confirmed, user_rule=user_rule,
            override_protection=override_protection)
        if ok:
            allowed.append(m)
        else:
            blocked.append({
                "id": m["id"],
                "reason": reason,
                "protected": (action in policy.DESTRUCTIVE_ACTIONS
                              and policy.is_protected(m)[0]),
            })
    return allowed, blocked


def finish_removal(conn, action: str, ids: list[int], *,
                   server_confirmed: list[int] | None = None,
                   ts: float | None = None) -> tuple[list[int], dict | None]:
    """按动作改本地状态，返回 (实际处理的 id, 撤销载荷)。不 commit，不写审计。

    - "purge"（不可逆）：必须传 server_confirmed —— 服务器确认已删除的本地 id。
      只 DELETE 这些行，返回 (server_confirmed, None)。
      没传 server_confirmed 就抛 ValueError：没有服务器的确认，本模块拒绝永久删除。
    - "soft_delete"：把 ids 的 deleted_ts 置为 ts（没传就用 time.time()），
      返回 (ids, {"ids": ids})。
    - 其他动作抛 ValueError。
    列表为空时不执行 SQL。
    """
    if action == "purge":
        if server_confirmed is None:
            raise ValueError(
                "purge 必须传 server_confirmed：没有服务器确认的 id，拒绝删除本地邮件")
        targets = list(server_confirmed)
        for chunk in chunk_ids(targets):
            ph = ",".join("?" * len(chunk))
            conn.execute(f"DELETE FROM messages WHERE id IN ({ph})", chunk)
        return targets, None
    if action == "soft_delete":
        targets = list(ids)
        if not targets:
            return targets, {"ids": targets}
        deleted_ts = time.time() if ts is None else ts
        for chunk in chunk_ids(targets):
            ph = ",".join("?" * len(chunk))
            conn.execute(f"UPDATE messages SET deleted_ts=? WHERE id IN ({ph})",
                         [deleted_ts] + chunk)
        return targets, {"ids": targets}
    raise ValueError(f"不支持的动作 {action}")


def record_action(action: str, *, actor: str, ids: list[int], reason: str,
                  undo_payload: dict | None, allowed: bool | None = None) -> int:
    """写一条动作审计，返回审计 id。

    tier 取 policy.ACTION_TIER.get(action, "C")；reversible 取 undo_payload is not None；
    allowed 没传时取 bool(ids)。
    """
    if allowed is None:
        allowed = bool(ids)
    return db.audit(action, actor=actor, tier=policy.ACTION_TIER.get(action, "C"),
                    target_ids=ids, reason=reason, allowed=allowed,
                    reversible=undo_payload is not None, undo_payload=undo_payload)


def restore(conn, ids: list[int]) -> int:
    """把回收站里的邮件放回收件箱，返回实际恢复的行数。不 commit，不写审计。"""
    if not ids:
        return 0
    restored = 0
    for chunk in chunk_ids(ids):
        ph = ",".join("?" * len(chunk))
        restored += conn.execute(
            f"UPDATE messages SET deleted_ts=0 WHERE id IN ({ph})", chunk).rowcount
    return restored


def undo(conn, audit_row, *, push_read_state: bool) -> tuple[int, list[int]]:
    """按审计记录里的撤销载荷恢复状态，并把这条审计标为已撤销。

    返回 (恢复的封数, 需要触发已读同步的账户 id)。不 commit，不写审计。
    调用方负责先确认这条记录存在、没撤销过、可撤销。
    push_read_state 为真时，撤销已读 / 未读会按恢复后的状态登记待推送
    （恢复成已读的登记 1，恢复成未读的登记 -1，与现有代码相同）。
    """
    payload = json.loads(audit_row["undo_payload"])
    restored = 0
    push_accounts: list[int] = []
    if audit_row["action"] == "soft_delete":
        ids = payload.get("ids", [])[:2000]
        for chunk in chunk_ids(ids):
            ph = ",".join("?" * len(chunk))
            restored += conn.execute(
                f"UPDATE messages SET deleted_ts=0 WHERE id IN ({ph})", chunk).rowcount
    elif audit_row["action"] in ("read", "unread"):
        pairs = [(int(mid), int(prev)) for mid, prev in (payload.get("unread") or {}).items()]
        for mid, prev in pairs:
            restored += conn.execute(
                "UPDATE messages SET unread=? WHERE id=?", (prev, mid)).rowcount
        if pairs and push_read_state:
            # 撤销把已读状态改了回来：按恢复后的状态登记待推送
            # （恢复成未读就标 -1，恢复成已读就标 1）
            for group, flag in (([mid for mid, prev in pairs if not prev], 1),
                               ([mid for mid, prev in pairs if prev], -1)):
                push_accounts += register_flag_push(conn, group, flag)
    conn.execute("UPDATE audit_log SET undone=1 WHERE id=?", (audit_row["id"],))
    return restored, push_accounts
