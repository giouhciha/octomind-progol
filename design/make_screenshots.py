from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920

# Paleta real del app
surface = (242, 248, 245)      # @color/surface
card = (255, 255, 255)         # @color/card
primary = (22, 166, 106)       # @color/primary  #16A66A
primary_dark = (8, 122, 74)    # @color/primary_dark #087A4A
ink = (23, 52, 42)             # @color/ink #17342A
muted = (96, 117, 109)         # @color/muted #60756D
divider = (210, 229, 220)      # @color/divider #D2E5DC
error = (197, 58, 58)          # @color/result_error #C53A3A
yellow = (255, 213, 79)        # @color/fixed_yellow #FFD54F
marked_blue = (214, 228, 255)  # @color/marked_row #D6E4FF
white = (255, 255, 255)
green_cell = (198, 230, 202)   # acierto (verde claro)
red_cell = (255, 205, 210)     # fallo (rojo claro)
grey_cell = (235, 238, 236)    # pendiente


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
    # señal
    d.polygon([(W - 150, 40), (W - 128, 40), (W - 128, 34), (W - 150, 34)], fill=white)
    d.polygon([(W - 142, 40), (W - 136, 40), (W - 136, 28), (W - 142, 28)], fill=white)
    # batería
    d.rectangle([W - 110, 26, W - 62, 50], outline=white, width=3)
    d.rectangle([W - 104, 32, W - 92, 44], fill=white)
    d.rectangle([W - 58, 32, W - 50, 44], fill=white)


def nav_bar(d):
    d.rectangle([0, H - 88, W, H], fill=card)
    d.rounded_rectangle([W // 2 - 100, H - 44, W // 2 + 100, H - 32], radius=10, fill=(200, 200, 200))


def new_screen():
    img = Image.new("RGB", (W, H), surface)
    d = ImageDraw.Draw(img)
    status_bar(d)
    nav_bar(d)
    return img, d


def title(d, text, y=116):
    d.text((48, y), text, fill=ink, font=load_font(56, bold=True))


def subtitle(d, text, y):
    d.text((48, y), text, fill=muted, font=load_font(38))


def card_rect(d, x, y, w, h, fill=card, radius=24):
    d.rounded_rectangle([x, y, x + w, y + h], radius=radius, fill=fill)


def button(d, x, y, w, h, text, bg=primary, fg=white, outline=None):
    d.rounded_rectangle([x, y, x + w, y + h], radius=14, fill=bg,
                        outline=outline if outline else bg, width=3)
    center(d, x + w / 2, y + h / 2 - 20, text, load_font(40, bold=True), fg)


def edittext(d, x, y, w, h, hint):
    d.rounded_rectangle([x, y, x + w, y + h], radius=14, fill=white, outline=divider, width=2)
    d.text((x + 28, y + h / 2 - 18), hint, fill=muted, font=load_font(36))


def spinner_box(d, x, y, w, h, value, label=None):
    d.rounded_rectangle([x, y, x + w, y + h], radius=14, fill=white, outline=divider, width=2)
    ty = y + h / 2 - 18
    if label:
        d.text((x + 28, ty), label, fill=muted, font=load_font(32))
        d.text((x + 28 + 220, ty), value, fill=ink, font=load_font(36, bold=True))
    else:
        d.text((x + 28, ty), value, fill=ink, font=load_font(36, bold=True))
    # flecha
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
FIXED = {0, 5, 9}  # favoritos (amarillo)


def pick_color(letter):
    return {"L": primary, "E": (255, 152, 0), "V": (33, 150, 243)}[letter]


# ---------------------------------------------------------------------------
# 1) LOGIN
# ---------------------------------------------------------------------------
def login():
    img, d = new_screen()
    center(d, W / 2, 480, "Octomind Progol", load_font(64, bold=True), ink)
    center(d, W / 2, 574, "Inicia sesión o regístrate para analizar", load_font(40), muted)

    x, w = 96, W - 192
    edittext(d, x, 700, w, 120, "Correo")
    edittext(d, x, 840, w, 120, "Contraseña")
    d.rounded_rectangle([x + 8, 986, x + 44, 1022], outline=primary, width=3)
    d.text((x + 70, 988), "Entrar con huella / rostro", fill=ink, font=load_font(36))

    button(d, x, 1060, w, 120, "Iniciar sesión")
    button(d, x, 1200, w, 120, "Registrarse", bg=white, fg=primary, outline=primary)

    center(d, W / 2, 1800, "Política de privacidad", load_font(34), muted)
    img.save("design/screenshots/screenshot_login.png")
    return img


# ---------------------------------------------------------------------------
# 2) MAIN (pronóstico + combinaciones)
# ---------------------------------------------------------------------------
def main():
    img, d = new_screen()
    title(d, "Octomind Progol")
    subtitle(d, "Pronóstico probabilístico del siguiente concurso", 196)

    x, w = 48, W - 96
    spinner_box(d, x, 260, w, 112, "Fin de Semana")

    # status
    card_rect(d, x, 396, w, 92)
    d.text((x + 28, 422), "Concurso publicado · Listo para calcular", fill=ink, font=load_font(34))

    # pronósticos + spinner
    d.text((x, 532), "Pronósticos", fill=ink, font=load_font(38))
    spinner_box(d, x + 250, 506, 260, 100, "20")

    button(d, x, 646, w, 120, "Calcular pronóstico")

    # Tarjeta combinaciones
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

    center(d, W / 2, ry + 14 * 56 + 20, "Pronóstico 1 de 20", load_font(36, bold=True), ink)
    img.save("design/screenshots/screenshot_main.png")
    return img


# ---------------------------------------------------------------------------
# 3) PROBABILIDADES
# ---------------------------------------------------------------------------
def probabilities():
    img, d = new_screen()
    title(d, "Octomind Progol")
    subtitle(d, "Pronóstico probabilístico del siguiente concurso", 196)

    x, w = 48, W - 96
    spinner_box(d, x, 260, w, 112, "Fin de Semana")

    cy = 400
    card_rect(d, x, cy, w, 1390)
    d.text((x + 28, cy + 24), "Probabilidades por casilla", fill=ink, font=load_font(46, bold=True))

    # encabezado de tabla
    hy = cy + 96
    d.text((x + 32, hy), "Partido", fill=muted, font=load_font(32, bold=True))
    d.text((x + 520, hy), "Local", fill=muted, font=load_font(32, bold=True))
    d.text((x + 660, hy), "Empate", fill=muted, font=load_font(32, bold=True))
    d.text((x + 800, hy), "Visita", fill=muted, font=load_font(32, bold=True))
    d.line([x + 16, hy + 44, x + w - 16, hy + 44], fill=divider, width=2)

    probs = [
        (58, 27, 15), (34, 38, 28), (55, 25, 20), (62, 22, 16),
        (49, 29, 22), (30, 33, 37), (52, 26, 22), (28, 40, 32),
        (47, 30, 23), (36, 35, 29), (31, 32, 37), (35, 36, 29),
        (54, 24, 22), (29, 31, 40),
    ]
    ry = hy + 60
    for i, (home, away, _pick) in enumerate(MATCHES):
        yy = ry + i * 84
        d.text((x + 32, yy), f"{home} vs {away}", fill=ink, font=load_font(34))
        lp, ep, vp = probs[i]
        # barra local
        d.rounded_rectangle([x + 500, yy + 8, x + 500 + int(lp * 2.2), yy + 40], radius=8, fill=primary)
        d.text((x + 660, yy), f"{ep}%", fill=ink, font=load_font(34))
        d.text((x + 800, yy), f"{vp}%", fill=ink, font=load_font(34))

    img.save("design/screenshots/screenshot_probabilities.png")
    return img


# ---------------------------------------------------------------------------
# 4) SEGUIMIENTO (comparación)
# ---------------------------------------------------------------------------
def tracking():
    img, d = new_screen()
    title(d, "Seguimiento de pronósticos")
    subtitle(d, "Captura los resultados conforme terminan los partidos", 196)

    x, w = 48, W - 96
    d.text((x, 250), "Concurso", fill=ink, font=load_font(38, bold=True))
    spinner_box(d, x, 280, w, 112, "Fin de Semana · #2353")

    # Comparación
    cy = 430
    card_rect(d, x, cy, w, 1350)
    d.text((x + 28, cy + 24), "Comparación de los 20 pronósticos", fill=ink, font=load_font(46, bold=True))
    d.text((x + 28, cy + 88), "Ranking · #3 de 20", fill=muted, font=load_font(34))

    rows = [
        (0, "América vs Toluca", "L", "L", "ok"),
        (1, "Chivas vs León", "E", "E", "ok"),
        (2, "Cruz Azul vs Pumas", "L", "V", "ko"),
        (3, "Monterrey vs Santos", "L", "L", "ok"),
        (4, "Tigres vs Puebla", "L", "L", "ok"),
        (5, "Atlas vs Querétaro", "V", "L", "ko"),
        (6, "Pachuca vs Juárez", "L", "L", "ok"),
        (7, "Tijuana vs Mazatlán", "E", "E", "ok"),
        (8, "Necaxa vs San Luis", "L", "L", "ok"),
        (9, "Santos vs Atlas", "E", "-", "pend"),
        (10, "León vs Monterrey", "V", "V", "ok"),
        (11, "Toluca vs América", "E", "-", "pend"),
    ]
    ry = cy + 150
    for i, (_idx, name, pron, real, status) in enumerate(rows):
        yy = ry + i * 86
        col = green_cell if status == "ok" else red_cell if status == "ko" else grey_cell
        d.rounded_rectangle([x + 16, yy - 4, x + w - 16, yy + 74], radius=12, fill=col)
        d.text((x + 40, yy), name, fill=ink, font=load_font(34))
        d.text((x + 700, yy), f"Pronóstico: {pron}", fill=ink, font=load_font(32))
        real_txt = real if real != "-" else "Pendiente"
        d.text((x + 700, yy + 40), f"Real: {real_txt}", fill=muted, font=load_font(32))
        mark = "✓" if status == "ok" else "✗" if status == "ko" else "·"
        mcol = primary if status == "ok" else error if status == "ko" else muted
        center(d, x + w - 80, yy + 20, mark, load_font(48, bold=True), mcol)

    img.save("design/screenshots/screenshot_tracking.png")
    return img


login()
main()
probabilities()
tracking()
print("4 capturas generadas en design/screenshots/")
