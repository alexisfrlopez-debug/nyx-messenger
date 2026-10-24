# -*- coding: utf-8 -*-
"""
Nyx — Fiche de présentation (génération PDF, voie Report du skill pdf).

Document vivant : régénérer à chaque version de la messagerie.
- Corps : ReportLab (TocDocTemplate + multiBuild, TOC auto)
- Couverture : Template 07 « Crystal Blue » (HTML -> html2poster.js -> pypdf)
- Palette corps : fixe Template 07 (bleu clair, même famille que la couverture)
"""
import os
import sys
import hashlib
import subprocess

PDF_SKILL_SCRIPTS = "/home/z/my-project/skills/pdf/scripts"
if PDF_SKILL_SCRIPTS not in sys.path:
    sys.path.insert(0, PDF_SKILL_SCRIPTS)

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch, cm
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak,
    KeepTogether, CondPageBreak, HRFlowable,
)
from reportlab.platypus.tableofcontents import TableOfContents
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfbase.pdfmetrics import registerFontFamily

# ----------------------------------------------------------------- chemins
OUT_DIR = "/home/z/my-project/download"
BODY_PDF = os.path.join(OUT_DIR, "fiche_nyx_body.pdf")
COVER_HTML = os.path.join(OUT_DIR, "fiche_nyx_cover.html")
COVER_PDF = os.path.join(OUT_DIR, "fiche_nyx_cover.pdf")
FINAL_PDF = os.path.join(OUT_DIR, "Nyx-fiche-de-presentation.pdf")

# ----------------------------------------------------------------- polices
FONT_DIR = "/usr/share/fonts"
pdfmetrics.registerFont(TTFont("FreeSerif", f"{FONT_DIR}/truetype/freefont/FreeSerif.ttf"))
pdfmetrics.registerFont(TTFont("FreeSerif-Bold", f"{FONT_DIR}/truetype/freefont/FreeSerifBold.ttf"))
pdfmetrics.registerFont(TTFont("FreeSerif-Italic", f"{FONT_DIR}/truetype/freefont/FreeSerifItalic.ttf"))
pdfmetrics.registerFont(TTFont("FreeSerif-BoldItalic", f"{FONT_DIR}/truetype/freefont/FreeSerifBoldItalic.ttf"))
pdfmetrics.registerFont(TTFont("NotoSerifSC", f"{FONT_DIR}/truetype/noto-serif-sc/NotoSerifSC-Regular.ttf"))
pdfmetrics.registerFont(TTFont("NotoSerifSC-Bold", f"{FONT_DIR}/truetype/noto-serif-sc/NotoSerifSC-Bold.ttf"))
pdfmetrics.registerFont(TTFont("DejaVuSans", f"{FONT_DIR}/truetype/dejavu/DejaVuSansMono.ttf"))
registerFontFamily("FreeSerif", normal="FreeSerif", bold="FreeSerif-Bold",
                   italic="FreeSerif-Italic", boldItalic="FreeSerif-BoldItalic")
registerFontFamily("NotoSerifSC", normal="NotoSerifSC", bold="NotoSerifSC-Bold")
registerFontFamily("DejaVuSans", normal="DejaVuSans", bold="DejaVuSans")

from pdf import install_font_fallback  # noqa: E402  (skill pdf scripts)
install_font_fallback()

# ------------------------------------------------- palette (Template 07)
# Palette fixe du Template 07 « Crystal Blue » — corps clair, même famille
# de teinte (~215 deg) que la couverture sombre. (cover.md, partie 07)
PAGE_BG      = colors.HexColor("#f5f8fc")   # XL
SECTION_BG   = colors.HexColor("#edf2f9")   # XL
CARD_BG      = colors.HexColor("#e4ecf5")   # L
TABLE_STRIPE = colors.HexColor("#eef3fa")   # L
HEADER_FILL  = colors.HexColor("#1a4a7a")   # M
BORDER       = colors.HexColor("#c0d0e2")   # S
ACCENT       = colors.HexColor("#2d7ab3")   # XS
TEXT_PRIMARY = colors.HexColor("#142840")
TEXT_MUTED   = colors.HexColor("#5a7a96")

TABLE_HEADER_COLOR = HEADER_FILL
TABLE_HEADER_TEXT  = colors.white
TABLE_ROW_EVEN     = colors.white
TABLE_ROW_ODD      = TABLE_STRIPE

# ----------------------------------------------------------------- layout
MARGIN = 1.0 * inch
PAGE_W, PAGE_H = A4
AVAIL_W = PAGE_W - 2 * MARGIN
AVAIL_H = PAGE_H - 2 * MARGIN
H1_ORPHAN = AVAIL_H * 0.25
MAX_KEEP_HEIGHT = PAGE_H * 0.4

DOC_TITLE = "Nyx — Fiche de présentation"
DOC_AUTHOR = "The Nyx Project Contributors"

# ----------------------------------------------------------------- styles
S = {}
S["body"] = ParagraphStyle("body", fontName="FreeSerif", fontSize=10.5, leading=17,
                           alignment=TA_JUSTIFY, textColor=TEXT_PRIMARY,
                           spaceBefore=0, spaceAfter=9)
S["lead"] = ParagraphStyle("lead", parent=S["body"], fontSize=11.5, leading=19,
                           textColor=TEXT_PRIMARY, spaceAfter=12)
S["h1"] = ParagraphStyle("h1", fontName="FreeSerif-Bold", fontSize=22, leading=27,
                         textColor=HEADER_FILL, spaceBefore=0, spaceAfter=4)
S["h2"] = ParagraphStyle("h2", fontName="FreeSerif-Bold", fontSize=15, leading=20,
                         textColor=TEXT_PRIMARY, spaceBefore=14, spaceAfter=7)
S["h3"] = ParagraphStyle("h3", fontName="FreeSerif-Bold", fontSize=11.5, leading=16,
                         textColor=TEXT_PRIMARY, spaceBefore=10, spaceAfter=5)
S["bullet"] = ParagraphStyle("bullet", parent=S["body"], alignment=TA_LEFT,
                             leftIndent=14, bulletIndent=2, spaceAfter=5,
                             bulletFontName="FreeSerif")
S["caption"] = ParagraphStyle("caption", fontName="FreeSerif", fontSize=8.5, leading=12,
                              alignment=TA_CENTER, textColor=TEXT_MUTED,
                              spaceBefore=3, spaceAfter=6)
S["cell"] = ParagraphStyle("cell", fontName="FreeSerif", fontSize=9, leading=12.5,
                           alignment=TA_LEFT, textColor=TEXT_PRIMARY)
S["cellc"] = ParagraphStyle("cellc", parent=S["cell"], alignment=TA_CENTER)
S["cellh"] = ParagraphStyle("cellh", fontName="FreeSerif-Bold", fontSize=9, leading=12.5,
                            alignment=TA_CENTER, textColor=colors.white)
S["cellfirst"] = ParagraphStyle("cellfirst", parent=S["cell"], fontName="FreeSerif-Bold",
                                textColor=HEADER_FILL)
S["quote"] = ParagraphStyle("quote", fontName="FreeSerif-Italic", fontSize=10.5,
                            leading=17, leftIndent=24, textColor=TEXT_MUTED,
                            alignment=TA_LEFT, spaceBefore=4, spaceAfter=4)
S["callout_title"] = ParagraphStyle("callout_title", fontName="FreeSerif-Bold",
                                    fontSize=10, leading=14, textColor=HEADER_FILL,
                                    spaceAfter=3)
S["callout_body"] = ParagraphStyle("callout_body", fontName="FreeSerif", fontSize=9.5,
                                   leading=15, textColor=TEXT_PRIMARY, alignment=TA_LEFT)
S["stat_big"] = ParagraphStyle("stat_big", fontName="FreeSerif-Bold", fontSize=19,
                               leading=23, textColor=ACCENT, alignment=TA_CENTER)
S["stat_label"] = ParagraphStyle("stat_label", fontName="FreeSerif", fontSize=8.5,
                                 leading=11.5, textColor=TEXT_MUTED, alignment=TA_CENTER)
S["toc_title"] = ParagraphStyle("toc_title", fontName="FreeSerif-Bold", fontSize=20,
                                leading=25, textColor=HEADER_FILL, spaceAfter=14)

# ------------------------------------------------------------- helpers
def head(text, level=0):
    """Titre avec attributs TOC (bookmark) — niveau 0 = H1, 1 = H2."""
    key = "h_%s" % hashlib.md5(text.encode()).hexdigest()[:8]
    style = S["h1"] if level == 0 else S["h2"]
    p = Paragraph('<a name="%s"/><b>%s</b>' % (key, text), style)
    p.bookmark_name = key
    p.bookmark_level = level
    p.bookmark_text = text
    p.bookmark_key = key
    return p


def h1_block(story, text, first_flowable=None):
    """H1 + filet accent + premier paragraphe, anti-orphelin (CondPageBreak)."""
    story.append(CondPageBreak(H1_ORPHAN))
    rule = HRFlowable(width="100%", color=ACCENT, thickness=1.2,
                      spaceBefore=2, spaceAfter=10)
    group = [head(text, 0), rule]
    if first_flowable is not None:
        group.append(first_flowable)
    story.extend(safe_keep_together(group))


def h2_block(story, text, first_flowable=None):
    group = [head(text, 1)]
    if first_flowable is not None:
        group.append(first_flowable)
    story.extend(safe_keep_together(group))


def safe_keep_together(elements):
    total_h = 0
    for el in elements:
        try:
            _, h = el.wrap(AVAIL_W, PAGE_H)
        except Exception:
            h = 20
        total_h += h
    if total_h <= MAX_KEEP_HEIGHT:
        return [KeepTogether(elements)]
    elif len(elements) >= 2:
        return [KeepTogether(elements[:2])] + list(elements[2:])
    return list(elements)


def para(story, text, style="body"):
    story.append(Paragraph(text, S[style]))


def bullets(story, items):
    for it in items:
        story.append(Paragraph(it, S["bullet"], bulletText="\u2022"))
    story.append(Spacer(1, 5))


def callout(story, title, text):
    """Encadré : bord gauche accent + fond teinté (max 1 par section)."""
    inner = [[Paragraph("<b>%s</b>" % title, S["callout_title"])],
             [Paragraph(text, S["callout_body"])]]
    t = Table(inner, colWidths=[AVAIL_W - 10], hAlign="CENTER")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), CARD_BG),
        ("LINEBEFORE", (0, 0), (0, -1), 3, ACCENT),
        ("LEFTPADDING", (0, 0), (-1, -1), 12),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (0, 0), 8),
        ("BOTTOMPADDING", (0, -1), (-1, -1), 9),
        ("TOPPADDING", (0, 1), (-1, 1), 0),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]))
    story.extend(safe_keep_together([Spacer(1, 6), t, Spacer(1, 10)]))


def stat_row(story, stats):
    """Rangée de statistiques : [(gros texte, libellé), ...]."""
    n = len(stats)
    gap = 8
    w = (AVAIL_W - gap * (n - 1)) / n
    cells, widths = [], []
    for i, (big, label) in enumerate(stats):
        cells.append([Paragraph("<b>%s</b>" % big, S["stat_big"]),
                      Paragraph(label, S["stat_label"])])
        widths.append(w)
        if i < n - 1:
            cells.append("")
            widths.append(gap)
    t = Table([cells], colWidths=widths, hAlign="CENTER")
    style = [("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
             ("TOPPADDING", (0, 0), (-1, -1), 9),
             ("BOTTOMPADDING", (0, 0), (-1, -1), 9)]
    for i in range(0, 2 * n - 1, 2):
        style += [("BACKGROUND", (i, 0), (i, 0), CARD_BG),
                  ("BOX", (i, 0), (i, 0), 0.8, BORDER)]
    t.setStyle(TableStyle(style))
    story.extend(safe_keep_together([Spacer(1, 4), t, Spacer(1, 12)]))


def table(story, header, rows, ratios, caption=None, first_bold=True,
          align_center_cols=None):
    """Tableau standardisé : cellules Paragraph, largeur = ratios x AVAIL_W."""
    assert abs(sum(ratios) - 1.0) < 0.01, "ratios doit sommer à 1"
    col_widths = [r * AVAIL_W for r in ratios]
    assert sum(col_widths) <= AVAIL_W + 0.5
    align_center_cols = align_center_cols or set()
    data = [[Paragraph("<b>%s</b>" % h, S["cellh"]) for h in header]]
    for r in rows:
        line = []
        for ci, c in enumerate(r):
            if ci == 0 and first_bold:
                line.append(Paragraph(c, S["cellfirst"]))
            elif ci in align_center_cols:
                line.append(Paragraph(c, S["cellc"]))
            else:
                line.append(Paragraph(c, S["cell"]))
        data.append(line)
    t = Table(data, colWidths=col_widths, hAlign="CENTER", repeatRows=1)
    style = [
        ("BACKGROUND", (0, 0), (-1, 0), TABLE_HEADER_COLOR),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("GRID", (0, 0), (-1, -1), 0.5, BORDER),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]
    for i in range(1, len(data)):
        style.append(("BACKGROUND", (0, i), (-1, i),
                      TABLE_ROW_EVEN if i % 2 == 1 else TABLE_ROW_ODD))
    t.setStyle(TableStyle(style))
    story.append(Spacer(1, 12))
    if caption:
        if len(rows) <= 15:
            story.extend(safe_keep_together([t, Paragraph(caption, S["caption"])]))
        else:
            story.append(t)
            story.append(Paragraph(caption, S["caption"]))
    else:
        story.append(t)
    story.append(Spacer(1, 12))


# ------------------------------------------------- gabarit avec TOC
class TocDocTemplate(SimpleDocTemplate):
    def afterFlowable(self, flowable):
        if hasattr(flowable, "bookmark_name"):
            level = getattr(flowable, "bookmark_level", 0)
            text = getattr(flowable, "bookmark_text", "")
            key = getattr(flowable, "bookmark_key", "")
            self.notify("TOCEntry", (level, text, self.page, key))


def on_page(canvas, doc):
    canvas.saveState()
    # En-tête : titre + filet accent
    canvas.setFont("FreeSerif", 7.5)
    canvas.setFillColor(TEXT_MUTED)
    canvas.drawString(MARGIN, PAGE_H - 0.62 * inch, DOC_TITLE)
    canvas.setStrokeColor(ACCENT)
    canvas.setLineWidth(1.2)
    canvas.line(MARGIN, PAGE_H - 0.70 * inch, PAGE_W - MARGIN, PAGE_H - 0.70 * inch)
    # Pied : auteur + numéro de page + filet léger
    canvas.setStrokeColor(BORDER)
    canvas.setLineWidth(0.5)
    canvas.line(MARGIN, 0.62 * inch, PAGE_W - MARGIN, 0.62 * inch)
    canvas.setFont("FreeSerif", 7.5)
    canvas.setFillColor(TEXT_MUTED)
    canvas.drawString(MARGIN, 0.44 * inch, DOC_AUTHOR)
    canvas.drawRightString(PAGE_W - MARGIN, 0.44 * inch, str(doc.page))
    canvas.restoreState()


# ===================================================================
#  CONTENU
# ===================================================================

def build_story():
    story = []

    # ---------------- Sommaire ----------------
    toc = TableOfContents()
    toc.levelStyles = [
        ParagraphStyle("TOC1", fontName="FreeSerif-Bold", fontSize=11, leading=15.5,
                       leftIndent=16, firstLineIndent=-16, spaceBefore=3,
                       textColor=TEXT_PRIMARY),
        ParagraphStyle("TOC2", fontName="FreeSerif", fontSize=9.5, leading=13,
                       leftIndent=32, firstLineIndent=-16, spaceBefore=1,
                       textColor=TEXT_MUTED),
    ]
    story.append(Paragraph("<b>Sommaire</b>", S["toc_title"]))
    story.append(HRFlowable(width="100%", color=ACCENT, thickness=1.2,
                            spaceBefore=0, spaceAfter=12))
    story.append(toc)
    story.append(PageBreak())

    # ================= 1. Nyx en 30 secondes =================
    p1 = Paragraph(
        "<b>Nyx</b> est une messagerie Android gratuite et open source, pensée pour "
        "les personnes qui veulent échanger <b>sans laisser de traces</b> : aucun "
        "numéro de téléphone, aucune adresse e-mail, aucune inscription ne sont "
        "demandés. Votre identité est une <b>adresse .onion</b> générée par "
        "l'application elle-même, et l'intégralité du trafic — messages, appels, "
        "mise à jour des contacts — passe exclusivement par le réseau anonyme "
        "<b>Tor</b>, embarqué dans l'application. Le contenu de chaque message est "
        "chiffré de bout en bout avec le protocole Signal, si bien que même les "
        "serveurs qui relaient les enveloppes n'y voient que des données opaques.",
        S["lead"])
    h1_block(story, "1. Nyx en 30 secondes", p1)

    stat_row(story, [
        ("0", "donnée personnelle demandée<br/>(ni numéro, ni e-mail)"),
        ("100 %", "du trafic via Tor<br/>(embarqué, sans app tierce)"),
        ("E2E", "chiffrement Signal Protocol<br/>sur chaque message"),
        ("AGPL", "licence libre<br/>code 100 % public"),
    ])

    para(story,
         "Le projet repose sur trois piliers, hérités directement du cahier des "
         "charges. D'abord, <b>l'anonymat réseau</b> : chaque utilisateur et chaque "
         "serveur est un service caché Tor (onion v3) ; aucune adresse IP n'est "
         "jamais exposée, dans aucun sens du flux. Ensuite, <b>le chiffrement en "
         "couches</b> : le protocole Signal (X3DH + Double Ratchet) protège le "
         "contenu des conversations, tandis que libsodium sécurise les canaux "
         "d'infrastructure entre serveurs et le stockage local. Enfin, <b>la "
         "réplication résiliente</b> : les serveurs Nyx maintiennent un journal "
         "d'événements signés qui se réplique en continu — la panne ou la perte "
         "d'un serveur n'entraîne ni interruption de service, ni perte de messages.")

    para(story,
         "Cette fiche décrit l'application telle qu'elle existe aujourd'hui "
         "(version 0.2.41 du 23 octobre 2026), la compare honnêtement aux "
         "messageries existantes, puis transforme les inconvénients constatés en "
         "axes d'amélioration pour les versions futures — en restant strictement "
         "dans le cadre du cahier des charges initial : anonymat intégral, "
         "chiffrement de bout en bout, hébergement 100 % réseau Tor. Elle est "
         "conçue pour être mise à jour à chaque nouvelle version du produit.")

    table(story,
          ["Élément", "Valeur"],
          [
              ["Nature", "Messagerie instantanée anonyme, chiffrée de bout en bout"],
              ["Plateforme", "Android (Kotlin + Jetpack Compose) ; console d'administration "
                             "Windows + Android pour l'opérateur"],
              ["Identité utilisateur", "Adresse onion v3 auto-générée (clé ed25519) ; "
                                       "alias public optionnel"],
              ["Chiffrement", "Signal Protocol (contenu) + libsodium (canaux, stockage) ; "
                              "base locale SQLCipher"],
              ["Réseau", "Tor 0.4.9.14 embarqué dans l'application (IPtProxy)"],
              ["Serveurs", "Nœuds nyx-server (Go) en onion v3, journal signé répliqué par "
                           "gossip, administrés depuis la console"],
              ["Licence", "AGPL-3.0-only, code et protocoles publics (dépôt nyx-messenger)"],
              ["Versions livrées", "Messagerie v0.2.49 · Console v0.8.12 · Serveur v0.2.38 "
                                   "(24 octobre 2026)"],
          ],
          [0.28, 0.72],
          caption="Tableau 1 — Fiche d'identité du projet Nyx au 24 octobre 2026.")

    # ================= 2. Comment ça marche =================
    p2 = Paragraph(
        "Nyx s'appuie sur une idée simple et forte : <b>votre nom EST votre clé "
        "publique</b>. Une adresse onion v3 de Tor encode littéralement une clé "
        "publique ed25519. En générant cette paire de clés sur votre téléphone, "
        "l'application obtient une identité vérifiable par construction : quiconque "
        "connaît votre adresse peut vérifier vos signatures, sans aucun annuaire "
        "central, aucune autorité, aucun compte à créer. La même mécanique sert "
        "pour les serveurs : un nœud Nyx est joignable et authentifié par sa seule "
        "adresse .onion.", S["body"])
    h1_block(story, "2. Comment ça marche", p2)

    h2_block(story, "2.1 Le trajet d'un message",
             Paragraph(
                 "Quand Alice écrit à Bob, son application chiffre le message avec "
                 "une session Signal établie avec Bob — y compris si Bob est "
                 "hors ligne, grâce aux pré-clés publiées à l'avance. L'enveloppe "
                 "chiffrée est déposée sur <b>n'importe lequel</b> des serveurs du "
                 "réseau (via Tor), qui la réplique automatiquement à tous les "
                 "autres. L'application de Bob récupère ses enveloppes dès qu'elle "
                 "revient en ligne, vérifie la signature d'Alice, déchiffre, "
                 "stocke localement et notifie. Personne, sur le chemin, ne peut "
                 "lire le contenu : les serveurs ne voient que des enveloppes "
                 "opaques et des métadonnées de routage minimisées.", S["body"]))
    para(story,
         "Ce modèle « dépose et réplique » a une conséquence importante sur la "
         "disponibilité : Bob n'a pas besoin d'être connecté au moment de l'envoi. "
         "Le message l'attend, répliqué sur plusieurs serveurs indépendants, "
         "jusqu'à sa prochaine connexion. C'est ce qui distingue Nyx des "
         "messageries pair-à-pair pures comme Briar, où les deux correspondants "
         "doivent être simultanément en ligne pour qu'un message passe.")

    h2_block(story, "2.2 Des serveurs répliqués, pas un serveur central",
             Paragraph(
                 "Chaque serveur Nyx (un binaire Go autonome) maintient un "
                 "<b>journal d'événements signés</b> : une liste append-only où "
                 "chaque événement porte la signature de son auteur et un numéro "
                 "de séquence. Les serveurs se synchronisent entre eux en continu "
                 "par « gossip » (push/pull chiffré). Un serveur compromis peut "
                 "retarder ou censurer, mais <b>jamais forger</b> un message au "
                 "nom d'un utilisateur : il ne possède pas sa clé privée. La perte "
                 "d'un serveur ne perd aucune donnée : les autres réplicas "
                 "possèdent le journal complet, et un serveur neuf le réaspire en "
                 "une seule synchronisation.", S["body"]))

    para(story,
         "L'ajout d'un serveur à la flotte est une opération d'administration "
         "realisée depuis la console nyx-admin (voir section 3.7) : identité "
         "générée sur l'hôte, publication de l'onion, adoption par les pairs, "
         "inscription dans la liste de rendez-vous signée que les applications "
         "embarquent pour démarrer.")

    callout(story, "Pour aller plus loin — sous le capot",
            "Le protocole Signal est utilisé au complet : X3DH pour l'établissement "
            "asynchrone du secret partagé (pré-clés publiées dans le journal), "
            "Double Ratchet pour la rotation de clé à chaque message (forward "
            "secrecy et auto-guérison après compromission). Les canaux "
            "serveur-serveur et le stockage local utilisent libsodium "
            "(XSalsa20-Poly1305). Zéro algorithme maison : uniquement des "
            "briques cryptographiques standardisées et éprouvées, édictées par les "
            "décisions d'architecture ADR-001 à ADR-016 du projet.")

    # ================= 3. Toutes les fonctionnalités =================
    p3 = Paragraph(
        "Cette section décrit l'intégralité des fonctions disponibles dans la "
        "version actuelle de l'application, regroupées par thème. Le tableau "
        "récapitulatif en fin de section permet de vérifier d'un coup d'œil ce qui "
        "existe, et depuis quelle version — les fonctions récentes sont celles "
        "livrées pendant la phase de consolidation terrain (v0.2.25 à v0.2.34).",
        S["body"])
    h1_block(story, "3. Toutes les fonctionnalités", p3)

    h2_block(story, "3.1 Identité, profil et contacts",
             Paragraph(
                 "Au premier lancement, l'application génère l'identité en quelques "
                 "secondes, sans demander la moindre information : pas de numéro, "
                 "pas d'e-mail, pas de pseudonyme obligatoire. Votre adresse onion "
                 "s'affiche avec un QR code, et vous pouvez la partager par le "
                 "canal de votre choix ; un lien d'invitation <b>nyx://add?o=…</b> "
                 "permet un ajout en un geste. Pour rendre l'échange plus facile "
                 "sans rien dévoiler de l'adresse complète, vous pouvez réserver "
                 "un <b>alias public unique</b> (pseudonyme) : vos contacts vous "
                 "trouvent par cet alias, avec des suggestions automatiques quand "
                 "celui choisi est déjà pris, et aucune énumération globale n'est "
                 "possible côté serveur.", S["body"]))
    para(story,
         "Le profil comprend un avatar, un nom d'affichage et des champs "
         "descriptifs dont la <b>visibilité est réglable champ par champ</b>. Les "
         "contacts se gèrent au fil de l'eau : renommage local, blocage d'un "
         "correspondant indésirable, et suggestion de réciprocité quand l'autre "
         "partie vous ajoute. La sauvegarde d'identité s'exporte sous forme "
         "chiffrée, protégée par une clé de récupération « nyx1 » à conserver "
         "précieusement — c'est la seule voie pour retrouver son compte sur un "
         "nouvel appareil.")

    h2_block(story, "3.2 Conversations",
             Paragraph(
                 "Les conversations individuelles bénéficient du chiffrement Signal "
                 "décrit plus haut, avec un retour visuel complet sur chaque "
                 "message : envoyé, distribué, lu. Vous pouvez répondre à un "
                 "message précis (citation), supprimer un message <b>pour tous</b> "
                 "après envoi, ou programmer des <b>messages éphémères</b> dont la "
                 "durée de vie démarre au moment du déchiffrement par le "
                 "destataire. Les conversations peu actives se rangent dans une "
                 "archive dédiée, et chaque contact peut être bloqué en un geste.",
                 S["body"]))

    h2_block(story, "3.3 Médias, fichiers et notes vocales",
             Paragraph(
                 "Photos, vidéos, fichiers et <b>notes vocales</b> s'échangent en "
                 "morceaux chiffrés (chunks) déposés sur le réseau de réplicas "
                 "comme les messages, puis sont reconstitués côté destinataire. "
                 "Le coffre local (media vault) chiffre les pièces jointes au "
                 "repos sur l'appareil. Une fonction inspirée des messageries "
                 "modernes complète l'ensemble : la <b>limite de vues</b> — un "
                 "média peut être envoyé en affichage permanent, en vue unique "
                 "ou avec une seule relecture ; le fichier est détruit côté "
                 "destinataire une fois le compteur épuisé (v0.2.28).", S["body"]))

    h2_block(story, "3.4 Appels et visio",
             Paragraph(
                 "Les appels audio et vidéo sont établis <b>directement entre les "
                 "deux téléphones</b>, d'un onion à l'autre : la sonnerie et la "
                 "signalisation transitent par le réseau Nyx (donc survivent à "
                 "l'écran d'accueil), puis le flux média emprunte une connexion "
                 "directe chiffrée entre les deux services onion — aucun relais "
                 "tierce n'y participe. Trois étapes ont consolidé cette "
                 "mécanique : le repli relais v0.2.44 fait transiter le média "
                 "par le réplica de flotte quand la connexion directe échoue "
                 "(l'appel aboutit même sur un réseau dégradé), NYX-LINK "
                 "(ADR-018, v0.2.46) permet de recevoir des appels entrants "
                 "sans être joignable depuis l'extérieur grâce à un lien "
                 "sortant persistant, et la v0.2.47 corrige enfin la cause "
                 "racine historique : depuis les premières versions, le service "
                 "onion du téléphone écoutait sur l'adresse IPv6 ::1 alors que "
                 "sa publication visait 127.0.0.1 — tor recevait « connexion "
                 "refusée » sur chaque flux entrant, ce qui rendait le média "
                 "direct (et donc le décrochage) impossible ; le correctif, "
                 "vérifié par des tests de liaison IPv4, est confirmé sur le "
                 "terrain (premières sondes de joignabilité réussies des deux "
                 "côtés). La v0.2.48 lève alors les derniers verrous "
                 "applicatifs du décrochage : le verrou de contact du média "
                 "est aligné sur celui de la sonnerie (un pair en attente "
                 "pouvait faire sonner puis se voir refuser le média), la "
                 "première tentative de connexion couvre la durée réelle du "
                 "rendezvous Tor (45 s), la permission micro — jamais "
                 "demandée depuis la première version — est désormais "
                 "sollicitée au moment d'appeler et de décrocher (l'appel "
                 "reste audible dans le sens qui marche si elle est refusée), "
                 "la visio reçoit son fil d'exécution caméra dédié, et la "
                 "récupération d'annuaire accepte les adresses de réplica "
                 "avec port.", S["body"]))

    h2_block(story, "3.5 Résilience et disponibilité",
             Paragraph(
                 "Le réseau Nyx ne dépend d'aucun serveur unique : si le réplica "
                 "que vous interrogez tombe, l'application bascule "
                 "automatiquement sur un autre (répartition en round-robin). La "
                 "liste de démarrage (rendez-vous) est <b>signée par la clé de "
                 "release</b> du projet et vérifiée par chaque client avant usage "
                 "(ADR-016) : personne ne peut substituer un faux réseau "
                 "d'amorçage. Enfin, chaque téléphone peut servir de "
                 "<b>réplica temporaire</b> pour ses contacts (v0.2.25) — fonction intégrée aux versions standard et test depuis la v0.2.35 (fin de la version « test 2 » dédiée) : "
                 "l'application héberge alors un petit réplica, annonce son "
                 "adresse aux contacts de façon chiffrée, et la console "
                 "d'administration agrège ces annonces pour les afficher dans la "
                 "flotte. Cette infrastructure est auto-réparatrice : les "
                 "backends locaux sont sondés par des chiens de garde (relance "
                 "automatique sur port neuf avec republication de l'onion, "
                 "v0.2.45), un cache de descripteur périmé est purgé par une "
                 "récupération forcée qui replonge un contact bloqué depuis "
                 "~24 heures vers ~2-3 minutes (v0.2.45), et côté serveur le "
                 "service onion survit au redéploiement du nœud (v0.2.37) — plus "
                 "de « fenêtre orpheline » pendant les mises à jour.", S["body"]))

    h2_block(story, "3.6 Sécurité sur l'appareil",
             Paragraph(
                 "Tor est <b>embarqué</b> dans l'application (version 0.4.9.14) : "
                 "aucune application tierce comme Orbot n'est nécessaire, et le "
                 "client HTTP refuse de fonctionner hors Tor — il échoue plutôt "
                 "que de fuiter en clair. L'accès à l'application se verrouille "
                 "par biométrie ou code ; la base de données locale est chiffrée "
                 "(SQLCipher) ; une fonction d'<b>effacement d'urgence</b> détruit "
                 "identité et messages en une action. Les mises à jour de "
                 "l'application se vérifient et s'installent depuis l'application "
                 "elle-même (chaîne à trois maillons insensible au quota partagé "
                 "de l'API GitHub via Tor, v0.2.42), et les builds de test embarquent un plan de test et "
                 "un journal de diagnostic persistant qui survit au redémarrage "
                 "du processus — conçus pour fiabiliser la correction des "
                 "anomalies terrain.", S["body"]))

    h2_block(story, "3.7 La console d'administration (nyx-admin)",
             Paragraph(
                 "Produit jumeau de la messagerie, la console nyx-admin (Windows "
                 "et Android) est l'outil de l'opérateur qui héberge et entretient "
                 "la flotte de serveurs. Elle affiche l'état de chaque nœud en "
                 "temps réel (joignabilité, version, charge), liste les réplicas "
                 "temporaires découverts, et permet de piloter chaque nœud via "
                 "des commandes signées sur un canal d'administration séparé "
                 "(onion d'admin avec authentification client Tor). Surtout, elle "
                 "industrialise l'exploitation : <b>déploiement d'un nouveau "
                 "réplica en un clic</b> via SSH, <b>mise à jour du nœud en un clic</b> "
                 "à identité conservée, sonde du réplica téléphoné auto-réparée "
                 "(v0.8.7), binaire serveur embarqué identifié et affiché AVANT "
                 "tout clic de mise à jour (v0.8.10 : impossible de redéployer "
                 "une version ancienne par erreur), boîte à outils de maintenance "
                 "en un clic (diagnostic, réparation, durcissement, tests de bout "
                 "en bout — 12 blocs éprouvés), et terminal SSH intégré. C'est "
                 "une pièce maîtresse du projet : sans elle, administrer une "
                 "flotte de services cachés serait artisanal.", S["body"]))

    table(story,
          ["Domaine", "Fonction", "Disponibilité"],
          [
              ["Identité", "Création d'identité sans inscription (onion v3)", "Depuis v0.1"],
              ["Identité", "Alias public unique + suggestions, anti-énumération", "v0.2.x"],
              ["Identité", "QR code + lien d'invitation nyx://add", "Depuis v0.1"],
              ["Identité", "Profil avec avatar et visibilité par champ", "v0.2.x"],
              ["Conversations", "Messages 1:1 chiffrés (Signal Protocol)", "Depuis v0.2"],
              ["Conversations", "Statuts envoyé / distribué / lu", "v0.2.x"],
              ["Conversations", "Suppression pour tous, réponses, citations", "v0.2.x"],
              ["Conversations", "Messages éphémères (TTL)", "Depuis v0.2"],
              ["Conversations", "Blocage de contacts, archivage", "v0.2.x"],
              ["Médias", "Photos, vidéos, fichiers en chunks chiffrés", "v0.2.x"],
              ["Médias", "Notes vocales (enregistrement + lecteur)", "v0.2.x"],
              ["Médias", "Limite de vues : permanent / vue unique / 1 relecture", "v0.2.28"],
              ["Appels", "Appels audio et vidéo P2P onion-vers-onion ; v0.2.44 : "
                 "repli relais média par le réplica de flotte quand le direct "
                 "échoue ; v0.2.47 : média direct réparé (cause racine IPv6 ::1) ; "
                 "v0.2.48 : décrochage débloqué côté applicatif (verrou de "
                 "contact aligné sur la sonnerie, 1er essai 45 s, permission "
                 "micro demandée, §3.4)", "v0.2.x · v0.2.34 · v0.2.44 · v0.2.48"],
              ["Résilience", "Bascule automatique entre réplicas (round-robin)", "v0.2.25"],
              ["Résilience", "Réplicas temporaires hébergés par les téléphones",
               "v0.2.25 · toutes versions v0.2.35"],
              ["Résilience", "Liste de rendez-vous signée par la clé de release", "v0.2.23"],
              ["Appareil", "Tor embarqué 0.4.9.14 (sans Orbot)", "v0.2.25"],
              ["Appareil", "Interface « Nuit de Nyx » : thème nuit/aube, "
                 "contrastes verrouillés par tests WCAG ; v0.2.37–v0.2.38 : récupération du descripteur onion tentée (HSFETCH) après redémarrage du correspondant ; v0.2.39 : syntaxe corrigée (adresse nue de 56 caractères exigée par le control-spec de tor — la récupération opère enfin) ; v0.2.38 : défilement garanti par construction (zone fixe plafonnée, listes toujours majoritaires à l'écran)", "v0.2.35–v0.2.39"],
              ["Appareil", "Verrou biométrique / code, base SQLCipher", "v0.2.x"],
              ["Appareil", "Sauvegarde chiffrée + clé de récupération nyx1", "v0.2.3"],
              ["Appareil", "Effacement d'urgence", "v0.2.x"],
              ["Appareil", "MISE À JOUR AUTOMATIQUE : le build TEST suit chaque release publiée sur GitHub (pré-releases incluses, vérification toutes les 30 min), le build standard suit les releases validées (non-pré-release, 6 h) ; détection + téléchargement via Tor, bannière « Nouvelle version prête — installer », installation confirmée par l'utilisateur ; correctif v0.2.40 : la permission d'installation (absente depuis v0.2.20) est enfin déclarée — Android 8+ bloquait silencieusement la mise à jour ; v0.2.42 : chaîne de secours à trois maillons (API → flux Atom → assets) contre le quota GitHub partagé par les IP de sortie Tor, reprise de téléchargement HTTP Range + vérification SHA-256", "v0.2.20 · v0.2.40 · v0.2.42"],
              ["Appareil", "TRANSPORT DE REPLAN P2P (ADR-017) : si le réplica est injoignable ou dégradé, les messages texte et la signalisation d'appel partent en DIRECT pair-à-pair (onion vers onion à travers Tor, inspiration Briar) — le pair en ligne les reçoit en ~5-15 s par la même porte de validation que son inbox (signature, verrous, dédoublonnage) ; ordre adaptatif : journal d'abord quand le réplica est sain, direct d'abord quand il est dégradé ; anti-churn tor : le démon n'est plus redémarré sur un simple réseau lent (seuil 5 échecs, démon prêt depuis ≥ 10 min, repos 10 min) — fin de la cascade « SOCKS server general failure » qui tuait les appels", "v0.2.41"],
              ["Résilience", "NYX-LINK — LE LIEN INVERSÉ (ADR-018) : appels et "
                 "signalisation entrants sans joignabilité entrante du téléphone "
                 "(lien sortant persistant vers le réplica, qui relaie) — "
                 "défense CGNAT/Doze", "v0.2.46"],
              ["Résilience", "AUTO-RÉPARATION : chiens de garde des backends locaux "
                 "(sonde TCP loopback, relance port neuf + republication onion) "
                 "et HSFETCH de guérison du cache descripteur (reconnexion en "
                 "~2-3 min au lieu de ~24 h après un descripteur orphelin)",
                 "v0.2.45"],
              ["Résilience", "Serveur sans fenêtre orpheline : le service onion survit "
                 "au redéploiement du nœud (Flags=Detach + adoption de "
                 "collision à l'identique)", "serveur v0.2.37"],
              ["Diagnostic", "Plan de test intégré, journal persistant, export de logs", "v0.2.33"],
              ["Administration", "Console nyx-admin : flotte, déploiement et mise à jour "
                                 "du nœud 1-clic SSH, boîte à outils, terminal ; v0.8.6 : sonde du réplica téléphoné auto-réparée (HSFETCH) ; v0.8.7 : correctif de syntaxe HSFETCH (adresse nue) — le réplica téléphoné passe enfin EN LIGNE après redémarrage de l'app ; v0.8.10 : binaire serveur embarqué affiché AVANT le clic de mise à jour ; v0.8.11 : binaire v0.2.37 embarqué + classification des échecs de sonde corrigée ; v0.8.12 : réplica téléphoné affiché « EN LIGNE (VIA LIEN INVERSÉ) » quand NYX-LINK le relie — fin du « oignon entrant injoignable »", "v0.8.12"],
          ],
          [0.20, 0.62, 0.18],
          caption="Tableau 2 — Récapitulatif des fonctionnalités de la messagerie et de la console "
                  "(version v0.2.49 / v0.8.12).",
          align_center_cols={2})

    # ================= 4. Comparatif =================
    p4 = Paragraph(
        "Pour situer Nyx, nous la comparons aux messageries les plus connues selon "
        "deux angles : ce qu'elles savent (ou exigent) de vous, et ce qu'elles "
        "font. Les concurrentes retenues couvrent le spectre complet : Signal "
        "(référence confidentialité grand public), WhatsApp et Telegram (géants "
        "grand public), Session (messagerie anonyme par onion), Briar (pair-à-pair "
        "Tor sans serveur) et Threema (payante, sans numéro). Les informations "
        "reflètent l'état public de ces applications en octobre 2026 ; elles "
        "évoluent avec le temps et cette fiche les mettra à jour.",
        S["body"])
    h1_block(story, "4. Comparatif avec les applications existantes", p4)

    table(story,
          ["Application", "Inscription", "Identité", "Transport réseau",
           "Architecture serveur", "Code source"],
          [
              ["Nyx", "Aucune", "Adresse onion v3 (clé auto-générée)",
               "Tor 100 %, embarqué", "Réplicas onion v3, journal signé répliqué",
               "AGPL-3.0, client + serveur"],
              ["Session", "Aucune", "ID Session (clé)",
               "Réseau Oxen (onion)", "Service nodes Oxen (réseau décentralisé)",
               "GPL, client (réseau tiers)"],
              ["Signal", "Numéro de téléphone", "Numéro de téléphone",
               "Internet direct (TLS)", "Serveurs centralisés Signal Foundation",
               "GPL/AGPL, client + serveur"],
              ["WhatsApp", "Numéro de téléphone", "Numéro de téléphone",
               "Internet direct (TLS)", "Serveurs centralisés Meta",
               "Client fermé"],
              ["Telegram", "Numéro de téléphone", "Numéro de téléphone",
               "Internet direct (MTProto)", "Cloud centralisé ; E2E seulement "
               "en chats secrets", "Client ouvert, serveur fermé"],
              ["Briar", "Aucune", "Clé locale",
               "Tor + Bluetooth/Wi-Fi local", "Aucun serveur (P2P pur)",
               "AGPL-3.0"],
              ["Threema", "Aucune (payante)", "ID Threema",
               "Internet direct (TLS)", "Serveurs centralisés suisses",
               "Client ouvert (serveur fermé)"],
          ],
          [0.14, 0.15, 0.19, 0.16, 0.21, 0.15],
          caption="Tableau 3 — Anonymat et confiance : ce que chaque application exige et voit.")

    para(story,
         "La lecture de ce premier tableau est sans appel sur la question de "
         "l'anonymat : seules Nyx, Session et Briar fonctionnent sans numéro de "
         "téléphone. Mais les trois se distinguent ensuite par leur réseau et "
         "leur modèle de disponibilité. Session s'appuie sur le réseau Oxen, un "
         "réseau tiers qu'il faut faire confiance ; Briar n'utilise aucun serveur "
         "— au prix d'une contrainte forte : les messages n'arrivent que si les "
         "deux correspondants sont en ligne au même moment. Nyx est la seule des "
         "trois à combiner un transport Tor de bout en bout, des serveurs "
         "eux-mêmes anonymes (des onions, donc traçables par personne) et une "
         "livraison asynchrone reposant sur la réplication.")

    table(story,
          ["Application", "Groupes", "Appels audio/vidéo", "Notes vocales",
           "Client bureau", "Multi-appareils", "Distribution"],
          [
              ["Nyx", "Non (phase 2)", "Oui (P2P onion, consolidation en cours)",
               "Oui", "Non (console admin)", "Non", "APK direct (F-Droid prévu)"],
              ["Session", "Oui", "Audio 1:1", "Oui", "Oui", "Non", "Magasins d'applications"],
              ["Signal", "Oui", "Oui", "Oui", "Oui (lié au téléphone)", "Oui (limité)",
               "Magasins d'applications"],
              ["WhatsApp", "Oui", "Oui", "Oui", "Oui", "Oui", "Magasins d'applications"],
              ["Telegram", "Oui (jusqu'à 200 000)", "Oui", "Oui", "Oui", "Oui",
               "Magasins d'applications"],
              ["Briar", "Oui (forums)", "Non", "Non", "Bêta", "Non", "F-Droid, site"],
              ["Threema", "Oui", "Oui", "Oui", "Oui", "Oui (payant)", "Magasins d'applications"],
          ],
          [0.14, 0.14, 0.19, 0.11, 0.16, 0.12, 0.14],
          caption="Tableau 4 — Fonctions et diffusion : maturité des usages courants.")

    para(story,
         "Ce second tableau met en évidence le prix du choix radical de Nyx : la "
         "maturité fonctionnelle. Groupes, client bureau et multi-appareils — "
         "des attentes désormais basiques pour le grand public — existent chez "
         "les concurrentes installées et pas encore dans Nyx. C'est un état de "
         "fait assumé par la feuille de route (section 6), pas un renoncement : "
         "le projet construit d'abord une base de confiance (anonymat, "
         "chiffrement, résilience) solide et auditable, puis étend les usages. "
         "À l'inverse, aucune messagerie grand public ne propose aujourd'hui le "
         "trio « zéro inscription + Tor intégral + livraison asynchrone "
         "répliquée » que Nyx offre d'entrée.")

    # ================= 5. Avantages et limites =================
    p5 = Paragraph(
        "Cette section consolide le bilan du comparatif sous une forme directe : "
        "ce que Nyx fait mieux, et ce qu'elle fait moins bien. Elle est volontairement "
        "honnête — chaque limite est un axe de travail explicitement repris dans "
        "la section suivante.", S["body"])
    h1_block(story, "5. Avantages et limites actuelles", p5)

    h2_block(story, "5.1 Avantages",
             Paragraph(
                 "Les points forts de Nyx découlent directement de ses choix "
                 "d'architecture, et plusieurs d'entre eux sont uniques sur le "
                 "marché des messageries. Ils se vérifient dans le code public du "
                 "projet, pas seulement dans une promesse marketing : chaque "
                 "garantie ci-dessous correspond à un mécanisme décrit dans la "
                 "documentation d'architecture et couvert par des tests "
                 "automatisés.", S["body"]))
    bullets(story, [
        "<b>Anonymat complet dès l'installation</b> — aucune donnée personnelle "
        "n'est demandée, à aucun moment ; même Threema exige un achat, Signal et "
        "WhatsApp un numéro.",
        "<b>Tor intégral et invisible pour l'utilisateur</b> — le réseau est "
        "embarqué, aucune app tierce ni configuration ; le trafic refuse de "
        "passer en clair.",
        "<b>Serveurs eux-mêmes anonymes et répliqués</b> — des onions v3 sans "
        "IP ; la panne d'un nœud est invisible pour les utilisateurs ; un "
        "nœud compromis ne peut ni lire, ni forger, ni perdre les messages.",
        "<b>Livraison asynchrone résiliente</b> — contrairement à Briar, vos "
        "messages vous attendent, répliqués, même après des heures hors ligne.",
        "<b>Chiffrement de référence et vérifiable</b> — protocole Signal "
        "complet + libsodium, zéro cryptographie maison, code AGPL auditable.",
        "<b>Console d'administration complète</b> — déploiement, diagnostic et "
        "réparation d'une flotte de services cachés en un clic : unique dans "
        "cette catégorie de projets.",
        "<b>Média à disparition contrôlée</b> — limite de vues avec destruction "
        "automatique côté destinataire, au-delà de l'éphémère classique.",
    ])

    h2_block(story, "5.2 Limites actuelles",
             Paragraph(
                 "Les limites ci-dessous sont connues, documentées, et pour "
                 "la plupart déjà inscrites dans la feuille de route du projet. "
                 "Les citer n'est pas un aveu de faiblesse mais la matière "
                 "première du plan d'amélioration de la section 6.", S["body"]))
    table(story,
          ["Limite constatée", "Détail et why"],
          [
              ["Latence supérieure", "Le trajet Tor multi-sauts puis la réplication "
               "ajoutent quelques secondes par rapport aux messageries centralisées ; "
               "c'est le prix direct de l'anonymat réseau intégral."],
              ["Pas de groupes", "Les conversations sont individuelles (1:1). Les "
               "groupes exigent des sender keys / MLS — prévus en phase 2 (roadmap)."],
              ["Android uniquement", "Pas de client iOS ni de client bureau pour les "
               "conversations ; la console de bureau est réservée à l'administration."],
              ["Un seul appareil par identité", "Pas de synchronisation multi-appareils ; "
               "le transfert passe par la sauvegarde chiffrée et la clé nyx1."],
              ["Appels en revalidation terrain", "La cause racine historique des appels "
               "sans média (écoute du service onion sur ::1 au lieu de "
               "127.0.0.1 sur Android) est corrigée en v0.2.47 et confirmée "
               "par les premières sondes joignables du terrain ; la v0.2.48 "
               "lève les derniers verrous applicatifs (verrou de contact du "
               "média aligné sur la sonnerie, fenêtre de connexion du premier "
               "essai portée à 45 s, permission micro enfin demandée) ; la "
               "v0.2.49 ajoute la preuve de build intégrée au démarrage "
               "(marqueur de câblage vérifiable) et la relance automatique du "
               "service onion jamais servi (course aux microdescripteurs au "
               "démarrage) — la confirmation de bout en bout de l'appel "
               "reste attendue."],
              ["Jeunesse du projet", "Version 0.2.x pré-release : pas encore d'audit "
               "externe, base d'utilisateurs très restreinte, documentation "
               "utilisateur à venir (v1.0)."],
              ["Installation manuelle", "Distribution par APK signé ; le passage sur "
               "F-Droid avec build reproductible est prévu pour la v1.0."],
              ["Perte de clé = perte de compte", "Aucune récupération par serveur, par "
               "conception ; repose sur la sauvegarde locale — la garde partagée "
               "(Shamir) est à l'étude pour la phase 2."],
              ["Dépendance à Tor", "La joignabilité des services dépend des annuaires "
               "Tor (HSDir) ; une panne de propagation Tor retarde la livraison, "
               "sans jamais la perdre."],
          ],
          [0.30, 0.70],
          caption="Tableau 5 — Limites actuelles de Nyx (v0.2.48) et leur contexte.")

    # ================= 6. Axes d'amélioration =================
    p6 = Paragraph(
        "Le cahier des charges du projet fixe le cap : anonymat intégral, "
        "chiffrement de bout en bout, hébergement exclusivement en réseau Tor, "
        "puis extension vers le marché pair-à-pair. Chaque inconvénient relevé "
        "dans la section précédente est donc transformé ici en axe concret, "
        "rattaché à une échéance de la feuille de route — et compatible avec le "
        "cahier des charges : aucun axe ne sacrifie l'anonymat au confort.",
        S["body"])
    h1_block(story, "6. Axes d'amélioration et feuille de route", p6)

    h2_block(story, "6.1 Des limites aux axes de travail",
             Paragraph(
                 "Le tableau suivant croise chaque limite avec la réponse prévue "
                 "par le projet, son horizon et son état d'avancement au "
                 "24 octobre 2026. Les horizons reprennent la roadmap officielle "
                 "du dépôt (docs/04-roadmap.md) et les décisions d'architecture "
                 "anticipées (ADR-006).", S["body"]))
    table(story,
          ["Limite (section 5.2)", "Axe d'amélioration", "Horizon", "État"],
          [
              ["Latence supérieure", "Connexions directes client-client (push P2P "
               "onion) en plus du dépôt répliqué ; polling adaptatif",
               "v0.2.x en cours", "Livré (ADR-017 v0.2.41, ADR-018 v0.2.46)"],
              ["Pas de groupes", "Groupes par sender keys (puis MLS), pièces "
               "jointes de groupe", "v0.3", "Conception actée (ADR)"],
              ["Appels en revalidation", "Confirmation terrain du correctif IPv6 "
               "(v0.2.47, sondes joignables au 24/10), des verrous "
               "applicatifs v0.2.48 et du build vérifié v0.2.49 "
               "(marqueur de câblage + onion servi confirmé) ; relais "
               "TURN (ADR-014) pour les réseaux hostiles",
               "v0.2.44-v0.2.49 puis phase 2",
               "Correctifs livrés, validation terrain en cours"],
              ["Android uniquement", "Étude d'un client bureau (le noyau réseau "
               "et les codecs sont déjà multiplateformes par conception)",
               "Post-v1.0 (à l'étude)", "Hors périmètre v1"],
              ["Un seul appareil", "Synchronisation multi-appareils sécurisée par "
               "dérivation de clés", "Phase 2 (à l'étude)", "Pré-étude"],
              ["Perte de clé = perte de compte", "Garde partagée (Shamir 2-sur-3) "
               "en complément de la clé nyx1", "Phase 2", "Documenté (modèle de "
               "sécurité)"],
              ["Jeunesse / confiance", "Audit croisé du modèle de sécurité, "
               "documentation utilisateur, 3 réplicas de production durables",
               "v1.0", "Planifié (roadmap)"],
              ["Installation manuelle", "F-Droid avec build reproductible et "
               "signature vérifiée", "v1.0", "Planifié (roadmap)"],
              ["Dépendance à Tor", "Réplicas embarqués déjà livrés (v0.2.25) ; "
               "publication de secours via liste de rendez-vous signée "
               "multi-chemins", "Continu", "Livré en partie"],
              ["Diagnostic terrain long", "Version du serveur affichée "
               "différemment selon les nœuds : alignement du numéro de version "
               "serveur sur les releases",
               "v0.2.35", "Résolu (v0.2.35)"],
          ],
          [0.26, 0.42, 0.17, 0.15],
          caption="Tableau 6 — Chaque limite devient un axe, aligné sur le cahier des charges "
                  "et la roadmap du projet.")

    h2_block(story, "6.2 La feuille de route du projet",
             Paragraph(
                 "Au-delà de la messagerie, la trajectoire du projet comporte "
                 "deux phases majeures, définies dès l'origine (ADR-006). Elles "
                 "sont rappelées ici parce qu'elles expliquent certains choix "
                 "techniques actuels : le journal d'événements a été conçu "
                 "extensible pour accueillir ces usages sans refonte.",
                 S["body"]))
    bullets(story, [
        "<b>Phase 1 (en cours) — fiabiliser la messagerie :</b> consolidation "
        "des appels, groupes (v0.3), checkpoints signés du journal pour "
        "l'élagage, tests d'intrusion d'un réplica hostile, puis v1.0 avec "
        "trois réplicas de production, F-Droid reproductible, documentation "
        "utilisateur et audit croisé.",
        "<b>Phase 2 — market store entre pairs :</b> pseudonyme commercial "
        "dérivé de l'identité, catalogue d'annonces signées, commandes à "
        "cohérence forte via un quorum d'arbitrage dédié, escrow multisig "
        "2-sur-3 (Monero et/ou Lightning), réputation construite uniquement "
        "sur des escrows réglés, litiges arbitrés par messagerie Nyx.",
        "<b>Phase 3 — trade de cryptomonnaies :</b> échanges crypto-crypto "
        "entre pairs par atomic swaps (Monero, Bitcoin/Lightning). Tout "
        "service impliquant du fiat est réglementé (en France : "
        "enregistrement PSAN/AMF, KYC/AML) et incompatible avec l'anonymat : "
        "le projet l'a posé comme garde-fou explicite — décision à prendre "
        "avant tout développement, jamais après.",
    ])
    para(story,
         "Cette discipline réglementaire fait partie du cahier des charges : "
         "la messagerie et le marché pair-à-pair restent anonymes par "
         "construction ; si un jour une activité réglementée voyait le jour, "
         "elle devrait être portée par une entité séparée, sans jamais "
         "affaiblir l'anonymat du cœur du produit.")

    h2_block(story, "6.3 Maintenance de cette fiche",
             Paragraph(
                 "La fiche est un document vivant : à chaque livraison de "
                 "version (messagerie, console ou serveur), la section 3 "
                 "(fonctionnalités), les tableaux 1 et 2 et la liste des axes "
                 "(tableau 6) sont mis à jour, et la version citée en couverture "
                 "change. L'historique ci-dessous permet de suivre ces mises à "
                 "jour au fil des releases du produit.", S["body"]))
    table(story,
          ["Édition", "Date", "Version décrite", "Changements"],
          [
              ["Première édition", "22 octobre 2026", "Messagerie v0.2.34 · "
               "Console v0.8.4 · Serveur v0.2.34",
               "Création de la fiche : description complète, comparatif "
               "six applications, axes d'amélioration."],
              ["2ᵉ édition", "22 octobre 2026", "Messagerie v0.2.36 · "
               "Console v0.8.5 · Serveur v0.2.35",
               "Fusion du réplica embarqué dans les versions standard et "
               "test (fin de « test 2 »), interface « Nuit de Nyx », mise à "
               "jour du nœud en un clic (console), correctif de lisibilité du "
               "mode sombre (contrastes WCAG verrouillés par tests), zip "
               "Windows retiré de la livraison console."],
              ["3ᵉ édition", "22 octobre 2026", "Messagerie v0.2.37 · "
               "Console v0.8.6 · Serveur v0.2.35",
               "Appels réparés : récupération automatique du descripteur "
               "onion (HSFETCH) après un redémarrage du correspondant — "
               "décrocher établit enfin le média ; sonde console du réplica "
               "téléphoné auto-réparée, « vu » à date réelle."],
              ["4ᵉ édition", "22 octobre 2026", "Messagerie v0.2.38 · "
               "Console v0.8.6 · Serveur v0.2.35",
               "Défilement garanti par construction sur l'écran principal : "
               "zone fixe plafonnée à 40 % de l'écran (elle défile en "
               "interne si besoin), listes toujours majoritaires — invariant "
               "verrouillé par tests JVM ; audit systématique du défilement "
               "de toutes les fenêtres (messager et console) ; cartes de "
               "diagnostic repliées par défaut sur le build de test."],
              ["5ᵉ édition", "22 octobre 2026", "Messagerie v0.2.39 · "
               "Console v0.8.7 · Serveur v0.2.35",
               "Correctif critique HSFETCH : la commande de récupération du "
               "descripteur onion (introduite v0.2.37/v0.8.6) était rejetée "
               "par tor — erreur 513 — car l'adresse était envoyée avec le "
               "suffixe « .onion » que le control-spec interdit (adresse nue "
               "de 56 caractères exigée) ; appels sans média et réplica "
               "« SOCKS injoignable » avaient cette cause commune ; tests de "
               "contrat réécrits : le faux control port valide la syntaxe "
               "comme le vrai tor."],
              ["6ᵉ édition", "23 octobre 2026", "Messagerie v0.2.40 · "
               "Console v0.8.7 · Serveur v0.2.35",
               "Mise à jour automatique de la version TEST (demande "
               "opérateur) : le build test suit désormais chaque release "
               "publiée sur GitHub — pré-releases incluses, vérification "
               "toutes les 30 minutes — avec téléchargement via Tor, "
               "bannière d'installation sur l'écran principal et "
               "notification dédiée ; le build standard conserve son canal "
               "validé (non-pré-release) ; correctif critique découvert au "
               "passage : la permission d'installation REQUEST_INSTALL_PACKAGES, "
               "absente du manifest depuis la v0.2.20, est enfin déclarée — "
               "Android 8+ rejetait silencieusement toute installation."],
              ["7ᵉ édition", "23 octobre 2026", "Messagerie v0.2.41 · "
               "Console v0.8.7 · Serveur v0.2.35",
               "Transport de replan P2P (demande opérateur : « 2e "
               "protocole de connexion… s\'inspirer de Briar ») : messages "
               "et signalisation d\'appel livrables en direct onion→onion "
               "quand les 2 téléphones sont en ligne et que les réplicas "
               "sont instables — redondance, stabilité et rapidité sans "
               "compromettre l\'anonymat (tout reste dans Tor, ADR-017) ; "
               "anti-churn tor : le démon n\'est plus redémarré sur un "
               "réseau simplement lent — cause racine de la cascade « SOCKS "
               "server general failure » qui rendait les appels "
               "indécrochables ; 378 tests JVM."],
              ["8ᵉ édition", "24 octobre 2026", "Messagerie v0.2.47 · "
               "Console v0.8.12 · Serveur v0.2.38",
               "Rattrapage de six versions : chaîne de mise à jour "
               "anti-quota GitHub (v0.2.42), publication onion stabilisée "
               "(v0.2.43), repli relais média des appels par le réplica "
               "(v0.2.44), guérison du cache descripteur + serveur sans "
               "fenêtre orpheline (v0.2.45), NYX-LINK le lien inversé "
               "(v0.2.46) ; v0.2.47 : la cause racine historique est enfin "
               "corrigée  — le service onion du téléphone écoutait sur IPv6 "
               "::1 pendant que sa publication visait 127.0.0.1, si bien que "
               "« oignon entrant injoignable » et « décrochage impossible » "
               "n'étaient qu'un seul et même défaut depuis les premières "
               "versions ; 486 tests JVM. Erratum (24/10 au soir) : la "
               "console courante est la v0.8.12 (NYX-LINK) — livrée session 68 "
               "mais jamais synchronisée, la synchro Drive a été rattrapée."],
              ["10ᵉ édition", "24 octobre 2026", "Messagerie v0.2.49 · "
               "Console v0.8.12 · Serveur v0.2.38",
               "Retour terrain v0.2.48 : deux causes trouvées — l'APK livré "
               "était un build mixte (3 correctifs sur 5 absents du dex, la "
               "reprise du pipeline sur mtimes ambiguës) et la course aux "
               "microdescripteurs laissait le service onion sans descripteur "
               "servi ; la v0.2.49 verrouille les deux : marqueur de câblage "
               "vérifiable dans l'export (preuve du build propre), reprise "
               "de build par empreinte SHA-256 avec porte critique, relance "
               "automatique du service « jamais servi » (DEL+ADD, même "
               "adresse) et vérité de publication (« onion CONFIRMÉ servi par "
               "les HSDirs ») ; validation de bout en bout attendue."],
              ["9ᵉ édition", "24 octobre 2026", "Messagerie v0.2.48 · "
               "Console v0.8.12 · Serveur v0.2.38",
               "Le correctif IPv6 de la 8ᵉ édition est confirmé par le terrain "
               "(premières sondes de joignabilité réussies sur les deux "
               "téléphones) ; la v0.2.48 lève les cinq derniers verrous "
               "applicatifs du décrochage révélés par les logs du 24/10 : verrou "
               "de contact du média plus strict que celui de la sonnerie, "
               "fenêtre de connexion du premier essai trop courte pour un "
               "rendezvous Tor à froid (12 s → 45 s), permission micro jamais "
               "demandée depuis la première version, caméra de visio sans fil "
               "d'exécution dédié, récupération d'annuaire rejetant les adresses "
               "de réplica avec port ; au passage, le lien inversé cible "
               "uniquement les serveurs de flotte (fin des 404 vers les "
               "téléphones des pairs) ; 490 tests JVM."],
          ],
          [0.20, 0.16, 0.30, 0.34],
          caption="Tableau 7 — Historique des éditions de cette fiche.")

    # ================= 7. Fiche technique =================
    p7 = Paragraph(
        "Cette dernière page rassemble les informations techniques et les "
        "ressources pour aller plus loin : composants logiciels, volume de tests, "
        "et pointeurs vers la documentation publique du projet. Elle s'adresse "
        "aux lecteurs techniques ; les autres sections suffisent pour comprendre "
        "et évaluer le produit.", S["body"])
    h1_block(story, "7. Fiche technique et ressources", p7)

    table(story,
          ["Composant", "Technologie", "Rôle"],
          [
              ["Application Android", "Kotlin, Jetpack Compose", "Interface, "
               "conversations, appels, coffre média"],
              ["Chiffrement contenu", "libsignal (X3DH + Double Ratchet)", "E2E "
               "des messages 1:1"],
              ["Chiffrement canaux", "libsodium / NaCl", "Gossip serveur-serveur, "
               "stockage, scellement"],
              ["Base locale", "Room + SQLCipher", "Messages, contacts, pièces "
               "jointes chiffrées au repos"],
              ["Réseau", "Tor 0.4.9.14 embarqué (IPtProxy)", "Transport anonyme "
               "sans application tierce"],
              ["Serveur", "Go (binaire statique unique)", "Onion v3, journal "
               "signé, gossip, API client/admin"],
              ["Console", "Compose Desktop (Windows) + APK Android", "Pilotage "
               "de flotte, SSH, boîte à outils"],
              ["Transports serveur", "Onion v3 (principal) · XMPP MUC (repli, "
               "opt-in) · TURN (phase 2)", "Résilience et contournement"],
          ],
          [0.24, 0.34, 0.42],
          caption="Tableau 8 — Composants techniques du système.")

    para(story,
         "La qualité est suivie en continu par une batterie de tests "
         "automatisés : 328 tests JVM exécutés sur deux variantes de build "
         "(656 exécutions), les tests Go du serveur, et 57 tests pour la "
         "console. Chaque livraison passe un audit de code strict (zéro défaut "
         "critique ou majeur toléré), et les APK sont signés avec la clé de "
         "release officielle — les sommes de contrôle SHA-256 sont publiées "
         "avec chaque version. Sur le terrain, les builds de test embarquent "
         "un plan de test guidé et un journal de diagnostic persistant qui "
         "alimente la correction des anomalies réelles.")

    para(story,
         "Toutes les ressources du projet sont publiques : le dépôt "
         "nyx-messenger contient le code de l'application et du serveur, la "
         "documentation d'architecture (modèle de sécurité, protocoles de "
         "messagerie et de réplication, feuille de route) et les seize "
         "décisions d'architecture (ADR-001 à ADR-016) qui justifient chaque "
         "choix technique. Les releases GitHub publient les APK signés et "
         "leurs sommes de contrôle. Le projet est en développement actif ; "
         "le journal des sessions documente son avancement de façon "
         "transparente.")

    return story


# ===================================================================
#  COUVERTURE — Template 07 « Crystal Blue » (cover.md)
# ===================================================================
COVER_TEMPLATE = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<link href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@400;700;900&family=Inter:wght@300;400;500;700&display=swap" rel="stylesheet">
<style>
@page { size: 794px 1123px; margin: 0; }
:root {
  --c-bg: #0a1628;
  --c-accent: #4da8da;
  --c-text: #e8f0f8;
  --c-muted: #7a9bb8;
  --c-glow: #2d7ab3;
}
html, body {
  margin: 0; padding: 0;
  width: 794px; height: 1123px;
  background: var(--c-bg);
  font-family: 'Inter', sans-serif;
}
@media screen {
  html { height: auto; display: flex; justify-content: center;
         min-height: 100vh; background: var(--c-bg); }
  body { transform-origin: top center;
         scale: min(1, calc(100vw / 794), calc(100vh / 1123));
         margin: 0 auto; box-shadow: 0 0 60px rgba(0,0,0,0.4); }
}
.poster { width: 794px; height: 1123px; position: relative;
          background: var(--c-bg); box-sizing: border-box;
          border: none; outline: none; box-shadow: none; }
/* Layer 1 — lueurs discrètes (dans les limites de la page) */
.glow-a, .glow-b { position: absolute; border-radius: 50%; }
.glow-a { top: 40px; left: 60px; width: 420px; height: 420px;
  background: radial-gradient(circle,
    rgba(45,122,179,0.08) 0%, rgba(45,122,179,0.0) 70%); }
.glow-b { bottom: 60px; right: 70px; width: 380px; height: 380px;
  background: radial-gradient(circle,
    rgba(45,122,179,0.07) 0%, rgba(45,122,179,0.0) 70%); }
/* Layer 2 — cadre lumineux */
.frame { position: absolute; top: 80px; bottom: 80px; left: 60px;
         right: 60px; border: 2px solid var(--c-accent); }
/* Layer 3 — contenu */
.kicker { position: absolute; top: 135px; left: 110px;
  font-size: 11pt; font-weight: 300; color: var(--c-accent);
  letter-spacing: 5px; text-transform: uppercase; max-width: 560px; }
.title { position: absolute; top: 236px; left: 110px;
  font-family: 'Playfair Display', serif; font-weight: 900;
  font-size: 64pt; line-height: 1.15; color: var(--c-text);
  max-width: 580px; text-shadow: 0 0 24px rgba(45,122,179,0.35); }
.subtitle { position: absolute; top: 435px; left: 110px;
  font-size: 17pt; font-weight: 400; color: var(--c-text);
  opacity: 0.9; max-width: 540px; line-height: 1.4; }
.summary { position: absolute; top: 545px; left: 110px;
  font-size: 12.5pt; line-height: 1.7; color: var(--c-muted);
  max-width: 520px; }
.org { position: absolute; top: 790px; left: 110px;
  font-size: 15pt; font-weight: 500; color: var(--c-text); }
.meta { position: absolute; top: 950px; left: 110px;
  font-size: 10pt; color: var(--c-muted); letter-spacing: 3px;
  text-transform: uppercase; }
</style>
</head>
<body>
<div class="poster">
  <div class="glow-a"></div>
  <div class="glow-b"></div>
  <div class="frame"></div>
  <div class="kicker">Fiche de présentation — Messagerie anonyme</div>
  <div class="title">Nyx</div>
  <div class="subtitle">Communiquer sans laisser de traces</div>
  <div class="summary">Messagerie Android de bout en bout, sans numéro, sans
    e-mail, sans inscription. Tout le trafic passe par le réseau Tor —
    messages, appels et visio compris — et les serveurs, eux-mêmes anonymes,
    se répliquent pour que rien ne se perde.</div>
  <div class="org">The Nyx Project Contributors</div>
  <div class="meta">Octobre 2026 · Versions v0.2.49 / v0.8.12 · AGPL-3.0</div>
</div>
</body>
</html>
"""


def render_cover():
    with open(COVER_HTML, "w", encoding="utf-8") as f:
        f.write(COVER_TEMPLATE)
    subprocess.run(
        ["node", os.path.join(PDF_SKILL_SCRIPTS, "html2poster.js"),
         COVER_HTML, "--output", COVER_PDF, "--width", "794px"],
        check=True,
    )


def merge_cover():
    from pypdf import PdfReader, PdfWriter
    A4_W, A4_H = 595.28, 841.89

    def normalize(page):
        w, h = float(page.mediabox.width), float(page.mediabox.height)
        if abs(w - A4_W) > 0.3 or abs(h - A4_H) > 0.3:
            page.scale_to(A4_W, A4_H)
        return page

    writer = PdfWriter()
    writer.add_page(normalize(PdfReader(COVER_PDF).pages[0]))
    for page in PdfReader(BODY_PDF).pages:
        writer.add_page(normalize(page))
    writer.add_metadata({
        "/Title": DOC_TITLE,
        "/Author": "Z.ai",
        "/Creator": "Z.ai",
        "/Subject": "Fiche de présentation de la messagerie Nyx : fonctionnalités, "
                    "comparatif, axes d'amélioration",
    })
    with open(FINAL_PDF, "wb") as f:
        writer.write(f)


# ===================================================================
def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    doc = TocDocTemplate(
        BODY_PDF, pagesize=A4,
        leftMargin=MARGIN, rightMargin=MARGIN,
        topMargin=MARGIN, bottomMargin=MARGIN,
        title=DOC_TITLE, author="Z.ai", creator="Z.ai",
        subject="Fiche de présentation de la messagerie Nyx",
    )
    doc.multiBuild(build_story(), onFirstPage=on_page, onLaterPages=on_page)
    print("corps :", BODY_PDF)
    render_cover()
    print("couverture :", COVER_PDF)
    merge_cover()
    print("final :", FINAL_PDF)


if __name__ == "__main__":
    main()
