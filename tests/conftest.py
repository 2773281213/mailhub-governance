"""测试进程隔离：任何应用模块导入前，强制使用临时数据库。"""
import os
import tempfile


_TEST_ROOT = tempfile.mkdtemp(prefix="mailhub-tests-")
os.environ["MAILHUB_DB"] = os.path.join(_TEST_ROOT, "mailhub.db")
os.environ.setdefault("MAILHUB_SECRET", "test-secret-for-unit-tests-only")
# 测试不依赖 MAILHUB_PUBLIC_URL 的默认值；需要默认行为的用例自行 delenv
os.environ.setdefault("MAILHUB_PUBLIC_URL", "https://mailhub.test")
