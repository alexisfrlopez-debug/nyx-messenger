#!/usr/bin/env python3
"""
ed25519lib — implémentation ed25519 pure Python (RFC 8032), stdlib uniquement.

Réutilise les primitives validées de scripts/ed25519_kat.py (pubkey) et ajoute
la SIGNATURE complète (§5.1.6) + la vérification (§5.1.7). Fonctions :
    ed_pubkey(seed32)  -> pub32
    ed_sign(seed32, msg, pub32=None) -> sig64
    ed_verify(pub32, msg, sig64) -> bool

Usage : from ed25519lib import ed_pubkey, ed_sign, ed_verify
"""
import hashlib

# --- Primitives du corps (identiques à ed25519_kat.py) ---
p = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
d = -121665 * pow(121666, p - 2, p) % p
I = pow(2, (p - 1) // 4, p)


def _inv(x):
    return pow(x, p - 2, p)


def _xrec(y):
    xx = (y * y - 1) * _inv(d * y * y + 1)
    x = pow(xx, (p + 3) // 8, p)
    if (x * x - xx) % p != 0:
        x = x * I % p
    if x % 2 != 0:
        x = p - x
    return x


BY = 4 * _inv(5) % p
BX = _xrec(BY)
B = (BX, BY % p, 1, BX * BY % p)
IDENT = (0, 1, 1, 0)


def _ed_add(pt1, pt2):
    x1, y1, z1, t1 = pt1
    x2, y2, z2, t2 = pt2
    a = (y1 - x1) * (y2 - x2) % p
    b = (y1 + x1) * (y2 + x2) % p
    c = 2 * t1 * t2 * d % p
    dd = 2 * z1 * z2 % p
    e, f, g, h = b - a, dd - c, dd + c, b + a
    return (e * f % p, g * h % p, f * g % p, e * h % p)


def _mul(pt, e):
    q = IDENT
    while e > 0:
        if e & 1:
            q = _ed_add(q, pt)
        pt = _ed_add(pt, pt)
        e >>= 1
    return q


def _point_compress(P):
    x, y, z, _ = P
    zi = _inv(z)
    x = x * zi % p
    y = y * zi % p
    return int.to_bytes(y | ((x & 1) << 255), 32, 'little')


def _point_decompress(s):
    y = int.from_bytes(s, 'little')
    sign = y >> 255
    y &= (1 << 255) - 1
    if y >= p:
        return None
    x = _xrec(y)
    if x & 1 != sign:
        x = p - x
    return (x, y, 1, x * y % p)


def _scalar(seed32: bytes) -> (int, bytes):
    h = hashlib.sha512(seed32).digest()      # 64 octets complets
    a = bytearray(h[:32])
    a[0] &= 248                              # clamp RFC 8032
    a[31] &= 127
    a[31] |= 64
    return int.from_bytes(a, 'little') % L, h[32:64]


def ed_pubkey(seed32: bytes) -> bytes:
    a, _ = _scalar(seed32)
    return _point_compress(_mul(B, a))


def ed_sign(seed32: bytes, msg: bytes, pub32: bytes = None) -> bytes:
    if pub32 is None:
        pub32 = ed_pubkey(seed32)
    a, prefix = _scalar(seed32)              # prefix = SHA512(seed)[32:64]
    r = int.from_bytes(hashlib.sha512(prefix + msg).digest(), 'little') % L
    R = _point_compress(_mul(B, r))
    k = int.from_bytes(hashlib.sha512(R + pub32 + msg).digest(), 'little') % L
    s = (r + k * a) % L
    return R + int.to_bytes(s, 32, 'little')


def ed_verify(pub32: bytes, msg: bytes, sig64: bytes) -> bool:
    if len(pub32) != 32 or len(sig64) != 64:
        return False
    A = _point_decompress(pub32)
    if A is None:
        return False
    R_s, s = sig64[:32], sig64[32:]
    R = _point_decompress(R_s)
    if R is None or int.from_bytes(s, 'little') >= L:
        return False
    k = int.from_bytes(hashlib.sha512(R_s + pub32 + msg).digest(), 'little') % L
    # vérification stricte : [s]B = R + [k]A
    left = _mul(B, int.from_bytes(s, 'little'))
    right = _ed_add(R, _mul(A, k))
    return _point_compress(left) == _point_compress(right)


if __name__ == '__main__':
    # Auto-test contre les vecteurs RFC 8032 (§7.1)
    kat = [
        ('9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60',
         'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a',
         '', 'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155'
             '5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b'),
        ('4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb',
         '3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c',
         '72', '92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da'
               '085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00'),
        ('c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7',
         'fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025',
         'af82', '6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac'
                 '18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a'),
    ]
    ok = 0
    for seed_h, pub_h, msg_h, sig_h in kat:
        seed = bytes.fromhex(seed_h)
        pub = ed_pubkey(seed)
        msg = bytes.fromhex(msg_h)
        sig = ed_sign(seed, msg, pub)
        good = pub.hex() == pub_h and sig.hex() == sig_h and ed_verify(pub, msg, sig)
        # tamper : la vérification doit échouer
        bad = not ed_verify(pub, msg + b'x', sig) if msg else not ed_verify(pub, b'x', sig)
        print(f"KAT {'OK' if good and bad else 'ÉCHEC'} (msg {len(msg)} o)")
        ok += good and bad
    print(f"{ok}/3 vecteurs RFC 8032 validés")
