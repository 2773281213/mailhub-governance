"""用合成分类语料（tests/classification_corpus.py）评估分类效果，打印混淆矩阵。

手动运行，不进 CI，不写数据库：

    python scripts/eval_classifier.py          # 只评估本地规则
    python scripts/eval_classifier.py --ai     # 同时让设置页配置的 AI 接口判定同一份语料

--ai 会读取 MAILHUB_DB 指向的数据库里的 AI 设置（需要 MAILHUB_SECRET 解密 API Key），
并把合成语料发往该接口；不会发送任何真实邮件。语料的期望值是本地规则应给出的分类，
少数样本（例如交给 AI 的「其他」）AI 给出更具体的分类是正常的，看逐条差异即可。
"""
import argparse
import os
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "tests"))

import rules  # noqa: E402
from classification_corpus import CORPUS, format_matrix  # noqa: E402

AI_BATCH = 8


def _rule_result(case: dict) -> dict:
    return rules.classify_detailed(case["subject"], case.get("body", ""), case["sender"],
                                   case.get("unsubscribe", False), [], case.get("signals"))


def rule_predictions() -> dict:
    return {case["id"]: _rule_result(case)["category"] for case in CORPUS}


def ai_predictions() -> dict:
    import ai  # 需要 MAILHUB_SECRET；只在 --ai 时导入

    try:
        cfg = ai.ai_config()
    except Exception as exc:
        sys.exit(f"读取 AI 设置失败：{exc}（检查 MAILHUB_DB 与 MAILHUB_SECRET）")
    if not cfg["enabled"]:
        sys.exit("AI 未启用：请先在设置页启用并配置接口")

    mails = []
    for index, case in enumerate(CORPUS, 1):
        ruled = _rule_result(case)
        signals = case.get("signals") or {}
        tags = (["群发"] if rules.is_bulk(signals, case.get("unsubscribe", False)) else [])
        mails.append({
            "id": index, "subject": case["subject"], "sender": case["sender"],
            "snippet": case.get("body", "")[:200], "body": case.get("body", ""),
            "rule_category": ruled["category"], "signals": tags,
            "otp_code": ruled["otp"], "has_otp": bool(ruled["otp"]),
        })

    predictions = {}
    for start in range(0, len(mails), AI_BATCH):
        for item in ai.classify_batch(mails[start:start + AI_BATCH]):
            case = CORPUS[int(item["id"]) - 1]
            if item.get("needs_review"):
                predictions[case["id"]] = "（无效）"
            else:
                predictions[case["id"]] = item["category"]
    return predictions


def report(name: str, predictions: dict) -> None:
    matrix: Counter = Counter(
        (case["expected"], predictions.get(case["id"], "（无）")) for case in CORPUS)
    correct = sum(n for (expected, got), n in matrix.items() if expected == got)
    print(f"\n== {name}：{correct}/{len(CORPUS)} 与期望一致")
    print(format_matrix(matrix))
    for case in CORPUS:
        got = predictions.get(case["id"], "（无）")
        if got != case["expected"]:
            print(f"  ✗ {case['id']}：期望 {case['expected']}，得到 {got}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--ai", action="store_true", help="同时评估当前配置的 AI 接口")
    args = parser.parse_args()
    report("本地规则", rule_predictions())
    if args.ai:
        report("AI", ai_predictions())


if __name__ == "__main__":
    main()
