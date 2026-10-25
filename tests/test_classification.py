"""分类基准与 AI 输入测试：用合成语料锁定本地规则的行为，检查发给 AI 的内容已遮蔽。

运行：python -m pytest tests/test_classification.py -q -s（-s 可看到混淆矩阵）
"""
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("MAILHUB_SECRET", "test-secret-for-unit-tests-only")

import pytest  # noqa: E402

import ai  # noqa: E402
import mail_client  # noqa: E402
import policy  # noqa: E402
import rules  # noqa: E402
from classification_corpus import CORPUS, format_matrix  # noqa: E402


def _classify(case: dict) -> dict:
    return rules.classify_detailed(case["subject"], case.get("body", ""), case["sender"],
                                   case.get("unsubscribe", False), [], case.get("signals"))


# ---------------- 语料基准 ----------------

@pytest.mark.parametrize("case", CORPUS, ids=[c["id"] for c in CORPUS])
def test_corpus_case(case):
    result = _classify(case)
    assert result["category"] == case["expected"], case.get("note", "")
    if "otp" in case:
        assert result["otp"] == case["otp"]


def test_corpus_has_no_protected_false_positives():
    """普通邮件被误判为 重要/安全/账单 会触发推送并挡住清理，必须为 0。"""
    matrix: Counter = Counter()
    wrong = []
    for case in CORPUS:
        got = _classify(case)["category"]
        matrix[(case["expected"], got)] += 1
        if got in policy.PROTECTED_CATEGORIES and case["expected"] not in policy.PROTECTED_CATEGORIES:
            wrong.append(case["id"])
    print("\n" + format_matrix(matrix))
    assert not wrong


def test_corpus_covers_every_rule_category():
    """「可疑」只由安全扫描和 AI 给出，其余分类在语料里都要有样本。"""
    assert {c["expected"] for c in CORPUS} == set(rules.CATEGORIES) - {"可疑"}


def test_corpus_ids_are_unique():
    ids = [c["id"] for c in CORPUS]
    assert len(ids) == len(set(ids))


def test_rule_confidence_reflects_signal_strength():
    otp = rules.classify_detailed("验证码 384756", "", "a@b.com", False, [])
    weak = rules.classify_detailed("Urgent: call me", "", "boss@corp.example", False, [])
    nothing = rules.classify_detailed("你好", "", "friend@mail.example", False, [])
    custom = rules.classify_detailed("x", "", "a@b.com", False,
                                     [{"field": "sender", "pattern": "a@b", "category": "通知"}])
    assert otp["confidence"] == rules.CONF_OTP
    assert weak["category"] == "重要" and weak["confidence"] == rules.CONF_WEAK
    assert nothing["category"] == "其他" and nothing["confidence"] == rules.CONF_DEFAULT
    assert custom["category"] == "通知" and custom["confidence"] == rules.CONF_USER_RULE


def test_classify_wrapper_matches_detailed():
    for case in CORPUS:
        detailed = _classify(case)
        assert rules.classify(case["subject"], case.get("body", ""), case["sender"],
                              case.get("unsubscribe", False), [], case.get("signals")) == (
            detailed["category"], detailed["otp"])


# ---------------- 邮件头群发信号 ----------------

RAW_BULK = (
    b"From: news@brand.example\r\n"
    b"Subject: hi\r\n"
    b"List-Id: <news.brand.example>\r\n"
    b"Precedence: bulk\r\n"
    b"Feedback-ID: 1:2:3\r\n"
    b"DKIM-Signature: v=1; a=rsa-sha256; d=sendgrid.net; s=s1; bh=abc=; b=xyz\r\n"
    b"\r\nbody\r\n"
)


def test_parse_extracts_header_signals():
    signals = mail_client.parse_message(RAW_BULK)["header_signals"]
    assert signals == {"list_id": True, "precedence_bulk": True, "auto_submitted": False,
                       "feedback_id": True, "esp": True}


def test_plain_mail_has_no_bulk_signals():
    raw = b"From: a@b.example\r\nSubject: x\r\nAuto-Submitted: no\r\n\r\nbody\r\n"
    signals = mail_client.parse_message(raw)["header_signals"]
    assert not any(signals.values())
    assert mail_client.unparsable_message()["header_signals"] == {}


def test_auto_submitted_is_detected():
    raw = b"From: ci@b.example\r\nSubject: x\r\nAuto-Submitted: auto-generated\r\n\r\nbody\r\n"
    assert mail_client.parse_message(raw)["header_signals"]["auto_submitted"] is True


# ---------------- 发给 AI 的正文摘录 ----------------

def test_excerpt_masks_code_secrets_links_and_quoted_mail():
    body = ("您的验证码为 384756，请访问 https://login.example.com/verify?token=abc 完成登录。\n"
            "初始密码：Abc12345\n"
            "> 上一封邮件的引用\n"
            "-----Original Message-----\n"
            "From: old@example.com\n"
            "旧邮件内容 SECRET-OLD")
    out = ai.prepare_excerpt(body, "384756")
    assert "384756" not in out and "[验证码]" in out
    assert "token=abc" not in out and "[链接:login.example.com]" in out
    assert "Abc12345" not in out
    assert "上一封邮件" not in out and "SECRET-OLD" not in out
    assert "\n" not in out


def test_excerpt_is_truncated():
    assert len(ai.prepare_excerpt("字" * 10000)) <= ai.AI_BODY_CHARS


def test_mask_does_not_touch_longer_numbers():
    assert ai.mask_secrets("订单 12345678，验证码 1234", "1234") == "订单 12345678，验证码 [验证码]"


def _fake_ai(monkeypatch, reply: str, captured: list | None = None):
    monkeypatch.setattr(ai, "ai_config", lambda: {
        "enabled": True, "base_url": "http://x/v1", "api_key": "k", "model": "m",
        "send_body": True})

    def chat(messages, **_kw):
        if captured is not None:
            captured.append(messages[1]["content"])
        return reply

    monkeypatch.setattr(ai, "chat", chat)


def test_user_message_carries_rule_hint_signals_and_masked_body(monkeypatch):
    captured: list = []
    _fake_ai(monkeypatch, "[]", captured)
    ai.classify_batch([{
        "id": 7, "subject": "验证码 384756", "sender": "noreply@x.example", "snippet": "",
        "body": "您的验证码为 384756，十分钟内有效", "rule_category": "验证码",
        "signals": ["自动发件人"], "otp_code": "384756", "has_otp": True,
    }])
    payload = captured[0]
    assert "384756" not in payload
    assert "规则初判=验证码" in payload and "信号=自动发件人" in payload
    assert "十分钟内有效" in payload


def test_ai_cannot_label_otp_without_local_code(monkeypatch):
    _fake_ai(monkeypatch, '[{"id":1,"category":"验证码","confidence":0.99,"importance":2}]')
    out = ai.classify_batch([{"id": 1, "subject": "s", "sender": "a@b.com", "snippet": ""}])
    assert out[0]["needs_review"] is True
    out = ai.classify_batch([{"id": 1, "subject": "s", "sender": "a@b.com", "snippet": "",
                              "has_otp": True}])
    assert out[0]["needs_review"] is False


def test_ai_suspicious_requires_risk_reason(monkeypatch):
    _fake_ai(monkeypatch, '[{"id":1,"category":"可疑","confidence":0.95,"importance":5}]')
    out = ai.classify_batch([{"id": 1, "subject": "s", "sender": "a@b.com", "snippet": ""}])
    assert out[0]["needs_review"] is True

    _fake_ai(monkeypatch, '[{"id":1,"category":"可疑","confidence":0.95,"importance":5,'
                          '"risk_reason":"仿冒品牌域名"}]')
    out = ai.classify_batch([{"id": 1, "subject": "s", "sender": "a@b.com", "snippet": ""}])
    assert out[0]["needs_review"] is False
    assert out[0]["risk_reason"] == "仿冒品牌域名"


def test_prompt_defines_every_category():
    for category in rules.CATEGORIES:
        assert f"- {category}：" in ai.CLASSIFY_SYSTEM
