#!/usr/bin/env python3
"""Vérification anti-build-mixte : les chaînes NOUVELLES de v0.2.62 doivent
être dans le dex (MUTF-8 = UTF-8 pour ces caractères), l'ancien marqueur absent."""
import zipfile

apk = 'livrables/dernieres-livrables/nyx-v0.2.62-20261029-064311-test.apk'
raw = zipfile.ZipFile(apk).read('classes.dex')

def has(s):
    return s.encode('utf-8') in raw

markers = [
    ('câblage v0.2.62 vérifié : BYE ARRÊTE LA SONNERIE', 'marqueur v0.2.62'),
    ('annulé par le pair (bye pendant la sonnerie)', 'fin de sonnerie sur BYE'),
    ('pair raccroché (BYE) — fin immédiate', 'BYE = fin immédiate'),
    ("demande de partage ANNULEE par l'utilisateur", 'annuler partage tel'),
    ('envoi trame 0x19 impossible', 'émission honnête'),
    ('rendu MJPEG : trame', 'preuve rendu MJPEG'),
    ('accusé « read » déposé pour', 'DiagLog accusé (partie stable)'),
    ('VALIDÉ PAR LOGS', 'carte TestPlan badge'),
]
for s, label in markers:
    print(('OK   ' if has(s) else 'MISS ') + label)
print('---')
print('ancien marqueur v0.2.61 encore présent :', has('câblage v0.2.61 vérifié'))
