"""Regenerate the bubble images from art/robot.webp.

off = the robot as drawn; on (recording) = the whole image mapped onto a purple ramp.
Writes app/src/main/res/drawable-nodpi/bubble_{off,on}.png (320 px) and art/preview.png.
Run from the repo root:  uv run --with pillow python art/make_bubble.py
"""
from PIL import Image, ImageOps

src = Image.open("art/robot.webp").convert("RGBA")
x0, y0, x1, y1 = src.getchannel("A").point(lambda v: 255 if v > 10 else 0).getbbox()
side = max(x1 - x0, y1 - y0) + 16
cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
off = src.crop((cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side))

gray = ImageOps.grayscale(off.convert("RGB"))
on = ImageOps.colorize(gray, black="#1E0550", white="#F3E8FF", mid="#7C2DF0", midpoint=110).convert("RGBA")
on.putalpha(off.getchannel("A"))

for name, img in (("off", off), ("on", on)):
    img.resize((320, 320), Image.LANCZOS).save(f"app/src/main/res/drawable-nodpi/bubble_{name}.png", optimize=True)

prev = Image.new("RGBA", (1100, 560), "#E9E9E9")
prev.alpha_composite(off.resize((520, 520), Image.LANCZOS), (20, 20))
prev.alpha_composite(on.resize((520, 520), Image.LANCZOS), (560, 20))
prev.save("art/preview.png")
