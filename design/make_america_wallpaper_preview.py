from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920

# Fondo real: el wallpaper del Club América
bg = Image.open("design/america_wallpaper.jpg").convert("RGB").resize((W, H))

# Colores del contenido sobre el fondo oscuro
on_bg = (255, 255, 255)        # título (blanco)
on_bg_muted = (217, 220, 227)  # subtítulo (gris claro)
card = (255, 255, 255)         # tarjetas
primary = (22, 166, 106)       # botones (verde, se mantiene)
primary_dark = (8, 122, 74)    # status bar (verde)
ink = (23, 52, 42)             # texto dentro de tarjetas (oscuro)
muted = (96, 117, 109)
divider = (210, 229, 220)
white = (255, 255, 255)
yellow = (255, 213, 79)


def load_font(size, bold=False):
    cands = (["C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf"]
             if bold else
             ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"])
    for p in cands:
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()


def center(d, cx, y, text, font, fill):
    b = d.textbbox((0, 0), text, font=font)
    d.text((cx - (b[2] - b[0]) / 2, y), text, fill=fill, font=font)


def status_bar(d):
    d.rectangle([0, 0, W, 76], fill=primary_dark)
    d.text((44, 22), "9:41", fill=white, font=load_font(34))
    d.polygon([(W - 150, 40), (W - 128, 40), (W - 128, 34), (W - 150, 34)], fill=white)
    d.polygon([(W - 142, 40), (W - 136, 40), (W - 136, 28), (W - 142, 28)], fill=white)
    d.rectangle([W - 110, 26, W - 62, 50], outline=white, width=3)
    d.rectangle([W - 104, 32, W - 92, 44], fill=white)
    d.rectangle([W - 58, 32, W - 50, 44], fill=white)


def nav_bar(d):
    d.rectangle([0, H - 88, W, H], fill=(245, 245, 245))
    d.rounded_rectangle([W // 2 - 100, H - 44, W // 2 + 100, H - 32], radius=10, fill=(200, 200, 200))


def card_rect(d, x, y, w, h, fill=card, radius=24):
    d.rounded_rectangle([x, y, x + w, y + h], radius=radius, fill=fill)


def button(d, x, y, w, h, text, bg=primary, fg=white):
    d.rounded_rectangle([x, y, x + w, y + h], radius=14, fill=bg)
    center(d, x + w / 2, y + h / 2 - 20, text, load_font(40, bold=True), fg)


def spinner_box(d, x, y, w, h, value):
    d.rounded_rectangle([x, y, x + w, y + h], radius=14, fill=white, outline=divider, width=2)
    d.text((x + 28, y + h / 2 - 18), value, fill=ink, font=load_font(36, bold=True))
    d.polygon([(x + w - 40, y + h / 2 - 6), (x + w - 20, y + h / 2 - 6), (x + w - 30, y + h / 2 + 6)], fill=muted)


MATCHES = [
    ("América", "Toluca", "L"),
    ("Chivas", "León", "E"),
    ("Cruz Azul", "Pumas", "L"),
    ("Monterrey", "Santos", "L"),
    ("Tigres", "Puebla", "L"),
    ("Atlas", "Querétaro", "V"),
    ("Pachuca", "Juárez", "L"),
    ("Tijuana", "Mazatlán", "E"),
    ("Necaxa", "San Luis", "L"),
    ("Santos", "Atlas", "E"),
    ("León", "Monterrey", "V"),
    ("Toluca", "América", "E"),
    ("Pumas", "Chivas", "L"),
    ("Juárez", "Pachuca", "V"),
]
FIXED = {0, 5, 9}


def pick_color(letter):
    return {"L": primary, "E": (255, 152, 0), "V": (33, 150, 243)}[letter]


def main():
    img = bg.copy()
    d = ImageDraw.Draw(img)
    status_bar(d)
    nav_bar(d)

    d.text((48, 116), "Octomind Progol", fill=on_bg, font=load_font(56, bold=True))
    d.text((48, 196), "Pronóstico probabilístico del siguiente concurso", fill=on_bg_muted, font=load_font(38))

    x, w = 48, W - 96
    spinner_box(d, x, 260, w, 112, "Fin de Semana")

    card_rect(d, x, 396, w, 92)
    d.text((x + 28, 422), "Concurso publicado · Listo para calcular", fill=ink, font=load_font(34))

    d.text((x, 532), "Pronósticos", fill=on_bg, font=load_font(38))
    spinner_box(d, x + 250, 506, 260, 100, "20")

    button(d, x, 646, w, 120, "Calcular pronóstico")

    cy = 806
    card_rect(d, x, cy, w, 950)
    d.text((x + 28, cy + 24), "Paquete de combinaciones", fill=ink, font=load_font(46, bold=True))
    d.text((x + 28, cy + 90), "Concurso estimado: #2354", fill=ink, font=load_font(36, bold=True))

    ry = cy + 150
    for i, (home, away, pick) in enumerate(MATCHES):
        yy = ry + i * 56
        if i in FIXED:
            d.rectangle([x + 8, yy - 4, x + w - 8, yy + 50], fill=yellow)
        d.text((x + 32, yy), f"{home} vs {away}", fill=ink, font=load_font(34))
        pc = pick_color(pick)
        d.rounded_rectangle([x + w - 92, yy - 2, x + w - 32, yy + 46], radius=12, fill=pc)
        center(d, x + w - 62, yy + 8, pick, load_font(34, bold=True), white)

    center(d, W / 2, ry + 14 * 56 + 20, "Pronóstico 1 de 20", load_font(36, bold=True), on_bg)
    img.save("design/screenshots/america_main.png")


def tracking():
    img = bg.copy()
    d = ImageDraw.Draw(img)
    status_bar(d)
    nav_bar(d)

    d.text((48, 116), "Seguimiento de pronósticos", fill=on_bg, font=load_font(56, bold=True))
    d.text((48, 196), "Captura los resultados conforme terminan los partidos", fill=on_bg_muted, font=load_font(38))

    x, w = 48, W - 96
    d.text((x, 250), "Concurso", fill=on_bg, font=load_font(38, bold=True))
    spinner_box(d, x, 280, w, 112, "Fin de Semana · #2353")

    cy = 430
    card_rect(d, x, cy, w, 1350)
    d.text((x + 28, cy + 24), "Comparación de los 20 pronósticos", fill=ink, font=load_font(46, bold=True))
    d.text((x + 28, cy + 88), "Ranking · #3 de 20", fill=muted, font=load_font(34))

    rows = [
        ("América vs Toluca", "L", "L", "ok"),
        ("Chivas vs León", "E", "E", "ok"),
        ("Cruz Azul vs Pumas", "L", "V", "ko"),
        ("Monterrey vs Santos", "L", "L", "ok"),
        ("Tigres vs Puebla", "L", "L", "ok"),
        ("Atlas vs Querétaro", "V", "L", "ko"),
        ("Pachuca vs Juárez", "L", "L", "ok"),
        ("Tijuana vs Mazatlán", "E", "E", "ok"),
        ("Necaxa vs San Luis", "L", "L", "ok"),
        ("Santos vs Atlas", "E", "-", "pend"),
        ("León vs Monterrey", "V", "V", "ok"),
    ]
    green_cell = (198, 230, 202)
    red_cell = (255, 205, 210)
    grey_cell = (235, 238, 242)
    ry = cy + 150
    for i, (name, pron, real, status) in enumerate(rows):
        yy = ry + i * 86
        col = green_cell if status == "ok" else red_cell if status == "ko" else grey_cell
        d.rounded_rectangle([x + 16, yy - 4, x + w - 16, yy + 74], radius=12, fill=col)
        d.text((x + 40, yy), name, fill=ink, font=load_font(34))
        d.text((x + 700, yy), f"Pronóstico: {pron}", fill=ink, font=load_font(32))
        real_txt = real if real != "-" else "Pendiente"
        d.text((x + 700, yy + 40), f"Real: {real_txt}", fill=muted, font=load_font(32))
        mark = "✓" if status == "ok" else "✗" if status == "ko" else "·"
        mcol = primary if status == "ok" else (197, 58, 58) if status == "ko" else muted
        center(d, x + w - 80, yy + 20, mark, load_font(48, bold=True), mcol)

    img.save("design/screenshots/america_tracking.png")


main()
tracking()
print("2 mockups con wallpaper generados")
