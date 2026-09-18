"""force_ipv4.py — 强制 python socket 走 IPv4 (国内 IPv6 优先会卡死 TLS 握手)。"""
import socket

_orig_getaddrinfo = socket.getaddrinfo


def _ipv4_only(host, port, family=0, type=0, proto=0, flags=0):
    return _orig_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)


socket.getaddrinfo = _ipv4_only
