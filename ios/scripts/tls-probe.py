#!/usr/bin/env python3
"""TLS probe for TLSIsolationTests (PROTOCOL.md §4.6 "Unlinkability").

Answers every HTTPS request with JSON saying which TCP connection carried it
and whether that connection resumed an earlier TLS session. `?close=1` closes
the connection after the response, so the next request needs a new handshake.
The certificate is a throwaway self-signed one for `localhost`, unless
TLS_PROBE_CERT and TLS_PROBE_KEY name another.

The probe speaks TLS 1.2 by default: URLSession (iOS 27) resumes TLS 1.2
sessions but did not offer TLS 1.3 resumption to this server at all, so only
TLS 1.2 shows whether a resumable session leaks between URLSessions.
TLS_PROBE_MAX_VERSION=1.3 allows TLS 1.3 anyway.

    ios/scripts/tls-probe.py [port]          # default 8443
    TEST_RUNNER_TLS_PROBE_URL=https://127.0.0.1:8443 xcodebuild test -scheme Us2usKit \\
        -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:Us2usKitTests/TLSIsolationTests
"""
import itertools
import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import threading

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8443
cert, key = os.environ.get("TLS_PROBE_CERT"), os.environ.get("TLS_PROBE_KEY")
if not cert:
    workdir = tempfile.mkdtemp(prefix="us2us-tls-probe-")
    cert, key = os.path.join(workdir, "cert.pem"), os.path.join(workdir, "key.pem")
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
         "-keyout", key, "-out", cert, "-days", "7", "-subj", "/CN=localhost",
         "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1", "-addext", "extendedKeyUsage=serverAuth"],
        check=True, capture_output=True)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
context.set_alpn_protocols(["http/1.1"])
if os.environ.get("TLS_PROBE_MAX_VERSION") != "1.3":
    context.maximum_version = ssl.TLSVersion.TLSv1_2
counter = itertools.count(1)


def handle(raw):
    try:
        tls = context.wrap_socket(raw, server_side=True)
    except (OSError, ssl.SSLError) as error:
        print("handshake failed:", error, flush=True)
        raw.close()
        return
    connection, reused = next(counter), tls.session_reused
    print(f"connection {connection}: {tls.version()} resumed={reused}", flush=True)
    stream = tls.makefile("rb")
    try:
        while True:
            request = stream.readline().split()
            if len(request) < 2:
                break
            headers = {}
            while (line := stream.readline()) not in (b"\r\n", b"\n", b""):
                name, _, value = line.decode("latin-1").partition(":")
                headers[name.strip().lower()] = value.strip()
            stream.read(int(headers.get("content-length", "0")))
            path = request[1].decode()
            close = "close=1" in path
            body = json.dumps({"connection": connection, "resumed": reused, "path": path}).encode()
            tls.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
                        b"Connection: %s\r\n\r\n" % (len(body), b"close" if close else b"keep-alive") + body)
            if close:
                break
    except (OSError, ssl.SSLError):
        pass
    finally:
        tls.close()


with socket.create_server(("127.0.0.1", port)) as server:
    print(f"TLS probe on https://127.0.0.1:{port}", flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()
