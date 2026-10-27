#!/usr/bin/env python3
"""
gen_rendezvous — liste de rendezvous signée Nyx (ADR-009 §3, ADR-016).

Format (v1) : deux fichiers inséparables —
    rendezvous.json      la liste (réplicas d'amorçage : onion[:port], note)
    rendezvous.json.sig  base64(signature ed25519 détachée) sur
                         b"nyx-rendezvous-v1|" + octets EXACTS du .json

La signature porte sur les OCTETS BRUTS (aucune canonicalisation JSON) :
éditeur et vérificateurs lisent les mêmes octets — zéro ambiguïté.

La clé ed25519 de release rendezvous (seed 32 o) vit HORS dépôt :
    /home/z/my-project/keystore/rendezvous-ed25519.json  (+ miroir Drive racine)
La clé PUBLIQUE est embarquée dans l'APK (BuildConfig.RENDEZVOUS_PUBKEY).

Usage :
  gen_rendezvous.py keygen [--out /home/z/my-project/keystore/rendezvous-ed25519.json]
  gen_rendezvous.py make --onion abc….onion[:port] [--note …] [--out rendezvous.json]
        [--onion … --note …] (répétable)
  gen_rendezvous.py sign  [--key FICHIER] <rendezvous.json>
  gen_rendezvous.py verify [--pub B64] <rendezvous.json> [<rendezvous.json.sig>]
"""
import argparse, base64, json, secrets, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ed25519lib import ed_pubkey, ed_sign, ed_verify  # noqa: E402

DOMAIN = b'nyx-rendezvous-v1|'
DEFAULT_KEY = '/home/z/my-project/keystore/rendezvous-ed25519.json'


def load_key(path):
    d = json.loads(Path(path).read_text())
    seed = base64.b64decode(d['seed_b64'])
    pub = ed_pubkey(seed)
    assert pub == base64.b64decode(d['pub_b64']), 'clé incohérente (seed ≠ pub)'
    return seed, pub


def cmd_keygen(args):
    out = Path(args.out)
    if out.exists():
        print(f"REFUS : {out} existe déjà (rotation = procédure documentée)"); return 1
    seed = secrets.token_bytes(32)
    pub = ed_pubkey(seed)
    out.write_text(json.dumps({
        'format': 'nyx-rendezvous-key-v1', 'seed_b64': base64.b64encode(seed).decode(),
        'pub_b64': base64.b64encode(pub).decode(),
    }, indent=2) + '\n')
    out.chmod(0o600)
    print(f"clé générée : {out}")
    print(f"PUBKEY (b64, à embarquer dans BuildConfig) : {base64.b64encode(pub).decode()}")
    return 0


def cmd_make(args):
    replicas = [{'onion': o.strip().lower(), **({'note': n} if n else {})}
                for o, n in args.onion if o.strip()]
    if not replicas:
        print("REFUS : aucune onion fournie (--onion)"); return 1
    doc = {'format': 'nyx-rendezvous', 'version': 1, 'generatedAt': args.generated_at or __import__('time').time(),
           'replicas': replicas}
    out = Path(args.out)
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + '\n')
    print(f"{out} écrit ({out.stat().st_size} o, {len(replicas)} réplica(s))")
    return 0


def cmd_sign(args):
    seed, pub = load_key(args.key)
    raw = Path(args.json).read_bytes()
    sig = ed_sign(seed, DOMAIN + raw, pub)
    Path(args.json + '.sig').write_bytes(base64.b64encode(sig) + b'\n')
    print(f"signature écrite : {args.json}.sig")
    print(f"PUBKEY (b64) : {base64.b64encode(pub).decode()}")
    return 0


def cmd_verify(args):
    raw = Path(args.json).read_bytes()
    sig_path = args.sig or (args.json + '.sig')
    sig = base64.b64decode(Path(sig_path).read_bytes().strip())
    if args.pub:
        pub = base64.b64decode(args.pub)
    else:
        _, pub = load_key(args.key)
    ok = ed_verify(pub, DOMAIN + raw, sig)
    print('VÉRIFICATION :', 'VALIDE' if ok else 'INVALIDE')
    return 0 if ok else 2


def main():
    ap = argparse.ArgumentParser(description='Liste de rendezvous signée Nyx')
    sub = ap.add_subparsers(dest='cmd', required=True)
    k = sub.add_parser('keygen'); k.add_argument('--out', default=DEFAULT_KEY)
    m = sub.add_parser('make'); m.add_argument('--onion', action='append', nargs=2, metavar=('ONION', 'NOTE'))
    m.add_argument('--out', default='rendezvous.json'); m.add_argument('--generated-at', type=int, default=None)
    s = sub.add_parser('sign'); s.add_argument('json'); s.add_argument('--key', default=DEFAULT_KEY)
    v = sub.add_parser('verify'); v.add_argument('json'); v.add_argument('sig', nargs='?')
    v.add_argument('--pub', default=None); v.add_argument('--key', default=DEFAULT_KEY)
    args = ap.parse_args()
    sys.exit({'keygen': cmd_keygen, 'make': cmd_make, 'sign': cmd_sign, 'verify': cmd_verify}[args.cmd](args))


if __name__ == '__main__':
    main()
