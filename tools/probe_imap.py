import socket, ssl

hosts = ["imapmail.163.com", "imap.163.com", "imapmail.126.com", "imap.126.com", "imapmail.yeah.net"]
for host in hosts:
    try:
        s = socket.create_connection((host, 993), timeout=15)
        ctx = ssl.create_default_context()
        ss = ctx.wrap_socket(s, server_hostname=host)
        banner = ss.recv(4096).decode(errors="replace").strip()
        ss.sendall(b"a1 CAPABILITY\r\n")
        cap = ss.recv(8192).decode(errors="replace")
        print("==", host)
        print("  banner:", banner[:200])
        for line in cap.splitlines():
            if "CAPABILITY" in line or "AUTH" in line:
                print("  ", line[:400])
        ss.close()
    except Exception as e:
        print("==", host, "ERR", type(e).__name__, str(e)[:160])
