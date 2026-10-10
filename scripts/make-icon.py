#!/usr/bin/env python3
"""Gera os icones do app a partir da arte da mascote.

Fonte unica: `mascote_anim/sprite_sheet.png` (mesma pasta que o app empacota),
frame 0 do `mascot_config.json` (o `idle_1`: olhos abertos, sorriso). Redesenhou a
mascote? Rode de novo e o icone acompanha.

    python3 scripts/make-icon.py

Saidas:
  app/src/main/res/mipmap-<dpi>/ic_launcher_foreground.png   (adaptive icon)
  app/src/main/res/mipmap-<dpi>/ic_launcher_monochrome.png   (icone tematico, A13+)
  app/src/main/res/drawable-nodpi/ic_launcher.png            (notificacao/tile)

O foreground escala com NEAREST: o pixel art tem que continuar duro, nada de
interpolacao borrando as bordas.

O icone normal leva a **figura inteira**; os de glifo pequeno (notificacao e
tematico) levam so a **cabeca** — num glifo de 24dp o corpo e as pernas viram um
blob e a cabeca com o laco e o que ainda se reconhece.
"""
import json
import pathlib
import sys
from collections import deque

try:
    from PIL import Image
except ImportError:
    sys.exit("precisa do Pillow: pip install pillow")

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANIM = ROOT / "mascote_anim"
RES = ROOT / "app/src/main/res"

# Adaptive icon: a arte vive no miolo de 72dp de uma tela de 108dp. O launcher
# recorta em circulo/squircle, entao desenhamos menor que o miolo pra nada ser
# cortado nas pontas.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
FOREGROUND_FRACTION = 0.62
GLYPH_FRACTION = 0.88
NOTIFICATION_PX = 144

# Onde a cabeca termina: a largura da linha cai abaixo desta fracao da largura
# mais larga do topo (o "pescoco"). Na arte atual a cabeca tem 20 px de largura e
# o pescoco 14 (0.70), entao 0.8 e folgado o bastante pra nao cortar a testa.
NECK_RATIO = 0.8

# Folga abaixo do pescoco. Sem ela a boca cai na ultima linha do recorte e o
# "inunda a partir da borda" a trata como contorno, entao ela nao vira furo.
NECK_MARGIN = 2

# A sombra do chao e desenhada em preto semitransparente; o personagem e opaco.
# So o opaco entra no perfil, senao a sombra (larga) viraria o "pescoco".
OPAQUE_ALPHA = 200

# Abaixo disso o pixel e "detalhe escuro" (olho, boca, cabelo, laco).
FEATURE_LUMA = 120


def load_idle_frame() -> Image.Image:
    config = json.loads((ANIM / "mascot_config.json").read_text(encoding="utf-8"))
    grid = int(config["grid_size"])
    frame = int(config["animations"]["idle"]["frames"][0])
    sheet = Image.open(ANIM / "sprite_sheet.png").convert("RGBA")
    x = frame * grid
    art = sheet.crop((x, 0, x + grid, grid))
    box = art.getchannel("A").getbbox()
    if box is None:
        sys.exit(f"o frame {frame} esta vazio — confira {ANIM / 'sprite_sheet.png'}")
    return art.crop(box)


def head(art: Image.Image) -> Image.Image:
    """Recorta a cabeca (do topo ate o pescoco), deixando corpo e sombra fora."""
    alpha = art.getchannel("A")
    widths = [
        len([x for x in range(art.width) if alpha.getpixel((x, y)) > OPAQUE_ALPHA])
        for y in range(art.height)
    ]

    top = max(1, art.height // 3)
    widest_y = max(range(top), key=lambda y: widths[y])
    widest = widths[widest_y]
    if widest == 0:
        return art

    for y in range(widest_y, art.height):
        if widths[y] < widest * NECK_RATIO:
            corte = min(art.height, y + NECK_MARGIN)
            return art.crop((0, 0, art.width, corte))
    return art


def place(art: Image.Image, side: int, fraction: float) -> Image.Image:
    """Centraliza a arte (escalada com NEAREST) numa tela quadrada transparente."""
    scale = (side * fraction) / max(art.width, art.height)
    size = (max(1, round(art.width * scale)), max(1, round(art.height * scale)))
    scaled = art.resize(size, Image.NEAREST)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(scaled, ((side - size[0]) // 2, (side - size[1]) // 2), scaled)
    return canvas


def silhouette(art: Image.Image, carve_features: bool = False) -> Image.Image:
    """So a forma, em branco: o sistema aplica o tint (notificacao/tema).

    Com `carve_features`, os detalhes escuros **fechados** (olhos, boca) viram
    furos. Sem isso o glifo e um blob: a cabeca de uma chibi e uma massa solida, e
    a 24dp ninguem enxerga um rosto nela.
    """
    alpha = art.getchannel("A")
    out = Image.new("RGBA", art.size, (255, 255, 255, 0))
    out.paste((255, 255, 255, 255), mask=alpha)

    if not carve_features:
        return out

    rgb = art.convert("RGB")
    escuro = lambda p: sum(p[:3]) / 3 < FEATURE_LUMA

    opacos = {
        (x, y)
        for y in range(art.height)
        for x in range(art.width)
        if alpha.getpixel((x, y)) > OPAQUE_ALPHA and escuro(rgb.getpixel((x, y)))
    }

    # Inunda a partir da borda: o que for escuro e alcancavel e contorno (cabelo,
    # laco). O que sobrar esta cercado de pele — ou seja, e olho ou boca.
    borda = [
        p
        for p in opacos
        if p[0] in (0, art.width - 1) or p[1] in (0, art.height - 1)
    ]
    fila = deque(borda)
    alcancado = set(borda)
    while fila:
        x, y = fila.popleft()
        for vizinho in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if vizinho in opacos and vizinho not in alcancado:
                alcancado.add(vizinho)
                fila.append(vizinho)

    for x, y in opacos - alcancado:
        out.putpixel((x, y), (255, 255, 255, 0))
    return out


def main() -> None:
    idle = load_idle_frame()
    face = silhouette(head(idle), carve_features=True)

    for dpi, side in DENSITIES.items():
        folder = RES / f"mipmap-{dpi}"
        folder.mkdir(parents=True, exist_ok=True)
        place(idle, side, FOREGROUND_FRACTION).save(folder / "ic_launcher_foreground.png")
        place(face, side, GLYPH_FRACTION).save(folder / "ic_launcher_monochrome.png")
        print(f"mipmap-{dpi}: foreground + monochrome em {side}x{side}")

    notif = RES / "drawable-nodpi"
    notif.mkdir(parents=True, exist_ok=True)
    place(face, NOTIFICATION_PX, GLYPH_FRACTION).save(notif / "ic_launcher.png")
    print(f"drawable-nodpi: ic_launcher.png em {NOTIFICATION_PX}x{NOTIFICATION_PX}")
    print(f"arte base: figura {idle.width}x{idle.height} px, cabeca {head(idle).size[0]}x{head(idle).size[1]} px")


if __name__ == "__main__":
    main()
