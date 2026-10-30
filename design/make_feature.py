from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 500
purple = (46, 16, 101, 255)        # #2E1065
white = (255, 255, 255, 255)
black = (26, 26, 26, 255)          # #1A1A1A
pink = (255, 179, 193, 220)        # #FFB3C1
muted = (206, 197, 236, 255)       # morado claro para el subtítulo

img = Image.new("RGB", (W, H), purple)
d = ImageDraw.Draw(img)


def load_font(size, bold=False):
    candidates = []
    if bold:
        candidates = ["C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf"]
    else:
        candidates = ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


# Pulpo-balón a la izquierda (centro ~(240, 250), radio ~155)
cx, cy, r = 240, 250, 155

# Tentáculos
d.ellipse([cx - 70, cy + r - 45, cx - 25, cy + r + 70], fill=white)
d.ellipse([cx - 28, cy + r - 40, cx + 8, cy + r + 75], fill=white)
d.ellipse([cx + 8, cy + r - 40, cx + 44, cy + r + 75], fill=white)
d.ellipse([cx + 44, cy + r - 45, cx + 89, cy + r + 70], fill=white)

# Balón
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=white)

# Pentágonos
d.polygon([(cx, cy + 58), (cx + 40, cy + 71), (cx + 25, cy + 104), (cx - 25, cy + 104), (cx - 40, cy + 71)], fill=black)
d.polygon([(cx - 112, cy - 90), (cx - 93, cy - 75), (cx - 98, cy - 48), (cx - 125, cy - 52)], fill=black)
d.polygon([(cx + 112, cy - 90), (cx + 93, cy - 75), (cx + 98, cy - 48), (cx + 125, cy - 52)], fill=black)
d.polygon([(cx - 78, cy + 60), (cx - 62, cy + 68), (cx - 67, cy + 92), (cx - 91, cy + 86)], fill=black)
d.polygon([(cx + 78, cy + 60), (cx + 62, cy + 68), (cx + 67, cy + 92), (cx + 91, cy + 86)], fill=black)

# Ojos kawai
d.ellipse([cx - 50, cy - 45, cx - 2, cy + 3], fill=black)
d.ellipse([cx + 2, cy - 45, cx + 50, cy + 3], fill=black)
d.ellipse([cx - 42, cy - 38, cx - 24, cy - 20], fill=white)
d.ellipse([cx + 24, cy - 38, cx + 42, cy - 20], fill=white)
d.ellipse([cx - 58, cy - 18, cx - 50, cy - 10], fill=white)
d.ellipse([cx + 50, cy - 18, cx + 58, cy - 10], fill=white)

# Mejillas
d.ellipse([cx - 88, cy + 5, cx - 56, cy + 37], fill=pink)
d.ellipse([cx + 56, cy + 5, cx + 88, cy + 37], fill=pink)

# Sonrisa
d.arc([cx - 30, cy + 12, cx + 30, cy + 44], start=0, end=180, fill=black, width=9)

# Texto a la derecha
font_big = load_font(78, bold=True)
font_mid = load_font(36)

d.text((480, 140), "Octomind Progol", fill=white, font=font_big)
d.text((482, 268), "Pronósticos estadísticos", fill=muted, font=font_mid)
d.text((482, 316), "para la quiniela Progol", fill=muted, font=font_mid)

img.save("design/feature_graphic_1024x500.png")
print("Generado: design/feature_graphic_1024x500.png")
