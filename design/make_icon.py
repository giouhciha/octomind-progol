from PIL import Image, ImageDraw

W = 512
img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

purple = (46, 16, 101, 255)      # #2E1065
white = (255, 255, 255, 255)
black = (26, 26, 26, 255)        # #1A1A1A
pink = (255, 179, 193, 220)      # #FFB3C1 (alpha suave)

# Fondo morado completo (Play Store: sin transparencia, sin esquinas redondeadas)
d.rectangle([0, 0, W, W], fill=purple)

# Tentáculos (4 elipses blancas saliendo por abajo)
d.ellipse([194, 360, 238, 470], fill=white)
d.ellipse([240, 366, 272, 478], fill=white)
d.ellipse([272, 366, 304, 478], fill=white)
d.ellipse([314, 360, 358, 470], fill=white)

# Balón (círculo blanco, centro 256,238 radio 150)
d.ellipse([106, 88, 406, 388], fill=white)

# Pentágonos negros (patrón de balón)
d.polygon([(256, 296), (292, 310), (278, 342), (234, 342), (220, 310)], fill=black)
d.polygon([(150, 152), (168, 166), (163, 192), (137, 188)], fill=black)
d.polygon([(362, 152), (344, 166), (349, 192), (375, 188)], fill=black)
d.polygon([(185, 300), (200, 308), (195, 330), (172, 324)], fill=black)
d.polygon([(327, 300), (312, 308), (317, 330), (340, 324)], fill=black)

# Ojos kawai (negros + brillos)
d.ellipse([184, 172, 232, 220], fill=black)
d.ellipse([280, 172, 328, 220], fill=black)
d.ellipse([207, 179, 225, 197], fill=white)
d.ellipse([303, 179, 321, 197], fill=white)
d.ellipse([196, 198, 204, 206], fill=white)
d.ellipse([292, 198, 300, 206], fill=white)

# Mejillas rosas
d.ellipse([154, 226, 186, 258], fill=pink)
d.ellipse([326, 226, 358, 258], fill=pink)

# Sonrisa
d.arc([228, 248, 284, 280], start=0, end=180, fill=black, width=9)

img.convert("RGB").save("design/app_icon_512.png")
print("PNG generado: design/app_icon_512.png")
