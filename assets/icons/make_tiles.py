import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
font_path, out_dir = sys.argv[1], sys.argv[2]
W, H, SS = 640, 360, 3  # 2x of the 320x180dp tile, drawn 3x supersampled

def base():
    w, h = W*SS, H*SS
    img = Image.new("RGB", (w, h))
    top, bot = (58, 58, 64), (14, 14, 16)   # graphite, like the Apple TV tile
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        c = tuple(int(top[i] + (bot[i]-top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    # soft top sheen
    sheen = Image.new("L", (w, h), 0)
    ImageDraw.Draw(sheen).ellipse([-w*0.2, -h*0.9, w*1.2, h*0.45], fill=26)
    sheen = sheen.filter(ImageFilter.GaussianBlur(70*SS))
    img = Image.composite(Image.new("RGB", (w, h), (255,255,255)), img, sheen)
    return img

def finish(img, name):
    img = img.resize((W, H), Image.LANCZOS)
    img.save(f"{out_dir}/{name}.png", optimize=True)

def wordmark(d, text, x, cy, size, weight_font):
    f = ImageFont.truetype(font_path, size*SS)
    try: f.set_variation_by_axes([weight_font])
    except Exception: pass
    bbox = d.textbbox((0, 0), text, font=f)
    d.text((x, cy - (bbox[1]+bbox[3])/2), text, font=f, fill=(245,245,247))
    return bbox[2]-bbox[0]

def measure(text, size, wt):
    f = ImageFont.truetype(font_path, size*SS)
    try: f.set_variation_by_axes([wt])
    except Exception: pass
    b = ImageDraw.Draw(Image.new("L",(1,1))).textbbox((0,0), text, font=f); return b[2]-b[0]

# TizenTube: rounded play glyph + wordmark
img = base(); d = ImageDraw.Draw(img)
g = 92*SS; gap = 26*SS; tw = measure("TizenTube", 74, 300)
x0 = (W*SS - (g + gap + tw)) / 2; cy = H*SS/2
d.rounded_rectangle([x0, cy-g*0.46, x0+g*1.0, cy+g*0.46], radius=26*SS, fill=(245,245,247))
tx = x0 + g*0.38; d.polygon([(tx, cy-g*0.22), (tx, cy+g*0.22), (tx+g*0.30, cy)], fill=(22,22,24))
wordmark(d, "TizenTube", x0+g+gap, cy, 74, 300)
finish(img, "banner_tizentube")

# Live TV: screen outline glyph with a red live dot + wordmark
img = base(); d = ImageDraw.Draw(img)
g = 100*SS; gap = 28*SS; tw = measure("Live TV", 78, 300)
x0 = (W*SS - (g + gap + tw)) / 2; cy = H*SS/2
d.rounded_rectangle([x0, cy-g*0.42, x0+g, cy+g*0.30], radius=14*SS, outline=(245,245,247), width=8*SS)
d.rounded_rectangle([x0+g*0.30, cy+g*0.40, x0+g*0.70, cy+g*0.40+8*SS], radius=4*SS, fill=(245,245,247))
r = 13*SS; d.ellipse([x0+g/2-r, cy-g*0.06-r, x0+g/2+r, cy-g*0.06+r], fill=(255,59,48))  # Apple system red
wordmark(d, "Live TV", x0+g+gap, cy, 78, 300)
finish(img, "banner_live_tv")

# ---- more tiles: (file, wordmark, glyph painter) with one shared layout
WHITE = (245,245,247)
def tile(name, text, glyph, g=96):
    img = base(); d = ImageDraw.Draw(img)
    G = g*SS; gap = 26*SS; size = 74
    while size > 50 and G + gap + measure(text, size, 300) > W*SS*0.72: size -= 2
    tw = measure(text, size, 300)
    x0 = (W*SS - (G + gap + tw)) / 2; cy = H*SS/2
    glyph(d, x0, cy, G)
    wordmark(d, text, x0+G+gap, cy, size, 300)
    finish(img, name)

def g_airplay(d, x, cy, G):   # rounded screen, gap in the bottom edge, triangle rising into it
    w = 7*SS
    top, bot = cy-G*0.40, cy+G*0.20
    d.rounded_rectangle([x+G*0.02, top, x+G*0.98, bot], radius=16*SS, outline=WHITE, width=w)
    # erase the bottom edge's middle with the tile's own background colour, row by row
    img = d._image
    for yy in range(int(bot-w-2*SS), int(bot+2*SS)):
        img.paste(img.getpixel((int(x-30*SS), yy)), (int(x+G*0.27), yy, int(x+G*0.73), yy+1))
    d.polygon([(x+G*0.5, cy-G*0.02), (x+G*0.25, cy+G*0.40), (x+G*0.75, cy+G*0.40)], fill=WHITE)

def g_store(d, x, cy, G):     # shopping bag
    w = 7*SS
    d.rounded_rectangle([x+G*0.06, cy-G*0.26, x+G*0.94, cy+G*0.46], radius=14*SS, fill=WHITE)
    d.arc([x+G*0.28, cy-G*0.56, x+G*0.72, cy-G*0.02], 180, 360, fill=WHITE, width=w)

def g_aerial(d, x, cy, G):    # sun over two mountain ridges
    r = G*0.12
    d.ellipse([x+G*0.62-r, cy-G*0.30-r, x+G*0.62+r, cy-G*0.30+r], fill=WHITE)
    d.polygon([(x, cy+G*0.40), (x+G*0.34, cy-G*0.10), (x+G*0.62, cy+G*0.40)], fill=WHITE)
    d.polygon([(x+G*0.40, cy+G*0.40), (x+G*0.70, cy+G*0.02), (x+G, cy+G*0.40)], fill=(200,200,206))

def g_folder(d, x, cy, G):    # folder
    d.rounded_rectangle([x, cy-G*0.36, x+G*0.46, cy-G*0.10], radius=8*SS, fill=WHITE)
    d.rounded_rectangle([x, cy-G*0.24, x+G, cy+G*0.40], radius=12*SS, fill=WHITE)

def g_download(d, x, cy, G):  # arrow down into a tray
    w = 8*SS
    d.line([x+G*0.5, cy-G*0.46, x+G*0.5, cy+G*0.10], fill=WHITE, width=w)
    d.polygon([(x+G*0.26, cy-G*0.06), (x+G*0.74, cy-G*0.06), (x+G*0.5, cy+G*0.22)], fill=WHITE)
    d.line([x+G*0.04, cy+G*0.18, x+G*0.04, cy+G*0.42, x+G*0.96, cy+G*0.42, x+G*0.96, cy+G*0.18], fill=WHITE, width=w, joint="curve")

tile("banner_airplay", "AirPlay", g_airplay)
tile("banner_fdroid", "F-Droid", g_store)
tile("banner_aerial", "Screensaver", g_aerial)
tile("banner_files", "Files", g_folder)
tile("banner_downloader", "Downloader", g_download)

# ---- glyph-only versions (final): the app name already appears above the dock / under grid tiles
def glyph_tile(name, glyph, g=150):
    img = base(); d = ImageDraw.Draw(img)
    G = g*SS
    glyph(d, (W*SS - G)/2, H*SS/2, G)
    finish(img, name)

def g_play(d, x, cy, G):
    d.rounded_rectangle([x, cy-G*0.36, x+G, cy+G*0.36], radius=int(G*0.24), fill=WHITE)
    tx = x + G*0.40
    d.polygon([(tx, cy-G*0.17), (tx, cy+G*0.17), (tx+G*0.27, cy)], fill=(0,0,0,0))

def g_live(d, x, cy, G):
    w = 9*SS
    d.rounded_rectangle([x, cy-G*0.40, x+G, cy+G*0.26], radius=int(G*0.12), outline=WHITE, width=w)
    d.rounded_rectangle([x+G*0.32, cy+G*0.36, x+G*0.68, cy+G*0.36+w], radius=w//2, fill=WHITE)
    r = G*0.11; d.ellipse([x+G/2-r, cy-G*0.07-r, x+G/2+r, cy-G*0.07+r], fill=(255,69,58))

glyph_tile("banner_tizentube", g_play, 130)
glyph_tile("banner_live_tv", g_live, 140)
glyph_tile("banner_airplay", g_airplay, 140)
glyph_tile("banner_fdroid", g_store, 120)
glyph_tile("banner_aerial", g_aerial, 140)
glyph_tile("banner_files", g_folder, 130)
glyph_tile("banner_downloader", g_download, 120)

def g_shield(d, x, cy, G):   # shield with a check: VPN
    w = 8*SS
    top, bot = cy-G*0.46, cy+G*0.46
    pts = [(x+G*0.5, top), (x+G*0.9, top+G*0.16), (x+G*0.86, cy+G*0.06), (x+G*0.5, bot), (x+G*0.14, cy+G*0.06), (x+G*0.1, top+G*0.16)]
    d.polygon(pts, fill=WHITE)
    d.line([x+G*0.33, cy+G*0.0, x+G*0.46, cy+G*0.13, x+G*0.68, cy-G*0.13], fill=(22,22,24), width=w, joint="curve")

glyph_tile("banner_vpn", g_shield, 120)

def g_airplay_glass(d, x, cy, G):   # rounded screen open at the bottom, triangle rising into it
    w = 7*SS
    top, bot = cy-G*0.40, cy+G*0.20
    r = 16*SS
    d.line([x+G*0.27, bot, x+G*0.02+r, bot], fill=WHITE, width=w)
    d.line([x+G*0.73, bot, x+G*0.98-r, bot], fill=WHITE, width=w)
    d.arc([x+G*0.02, bot-2*r, x+G*0.02+2*r, bot], 90, 180, fill=WHITE, width=w)
    d.arc([x+G*0.98-2*r, bot-2*r, x+G*0.98, bot], 0, 90, fill=WHITE, width=w)
    d.line([x+G*0.02, bot-r, x+G*0.02, top+r], fill=WHITE, width=w)
    d.line([x+G*0.98, bot-r, x+G*0.98, top+r], fill=WHITE, width=w)
    d.arc([x+G*0.02, top, x+G*0.02+2*r, top+2*r], 180, 270, fill=WHITE, width=w)
    d.arc([x+G*0.98-2*r, top, x+G*0.98, top+2*r], 270, 360, fill=WHITE, width=w)
    d.line([x+G*0.02+r, top, x+G*0.98-r, top], fill=WHITE, width=w)
    d.polygon([(x+G*0.5, cy-G*0.02), (x+G*0.25, cy+G*0.40), (x+G*0.75, cy+G*0.40)], fill=WHITE)

def g_shield_glass(d, x, cy, G):   # shield outline with a check (no dark fill to clash with glass)
    w = 8*SS
    top, bot = cy-G*0.46, cy+G*0.46
    pts = [(x+G*0.5, top), (x+G*0.9, top+G*0.16), (x+G*0.86, cy+G*0.06), (x+G*0.5, bot), (x+G*0.14, cy+G*0.06), (x+G*0.1, top+G*0.16), (x+G*0.5, top)]
    d.line(pts, fill=WHITE, width=w, joint="curve")
    d.line([x+G*0.33, cy+G*0.0, x+G*0.46, cy+G*0.13, x+G*0.68, cy-G*0.13], fill=WHITE, width=w, joint="curve")

# ---- liquid-glass tiles (RGBA): clear frost, top gloss, bright rim, glyph with a soft shadow
def glass_base():
    w, h = W*SS, H*SS
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    frost = Image.new("L", (w, h))
    fp = frost.load()
    for y in range(h):
        a = int(66 - 40 * y / (h - 1))          # 26% at the top -> 10% at the bottom
        for x in range(w):
            fp[x, y] = a
    img.paste(Image.new("RGBA", (w, h), (255, 255, 255, 255)), (0, 0), frost)
    gloss = Image.new("L", (w, h), 0)
    ImageDraw.Draw(gloss).ellipse([-w*0.25, -h*1.05, w*1.25, h*0.42], fill=46)
    gloss = gloss.filter(ImageFilter.GaussianBlur(26*SS))
    img = Image.alpha_composite(img, Image.merge("RGBA", (*Image.new("RGB", (w, h), (255, 255, 255)).split(), gloss)))
    rim = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    r = round(14 / 180 * W) * SS  # match the tile's 14dp rounded outline exactly
    ImageDraw.Draw(rim).rounded_rectangle([SS, SS, w-1-SS, h-1-SS], radius=r, outline=(255, 255, 255, 110), width=2*SS)
    return Image.alpha_composite(img, rim)

def glass_tile(name, glyph, g=150):
    base = glass_base()
    w, h = base.size
    G = g*SS
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glyph(ImageDraw.Draw(layer), (w - G)/2, h/2, G)
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    shadow.putalpha(layer.split()[3].point(lambda a: a * 0.45).filter(ImageFilter.GaussianBlur(6*SS)))
    out = Image.alpha_composite(base, shadow.transform((w, h), Image.AFFINE, (1, 0, 0, 0, 1, -3*SS)))
    out = Image.alpha_composite(out, layer)
    out.resize((W, H), Image.LANCZOS).save(f"{out_dir}/{name}.png", optimize=True)

for n, g, s in [("banner_tizentube", g_play, 130), ("banner_live_tv", g_live, 140), ("banner_airplay", g_airplay_glass, 140),
                ("banner_fdroid", g_store, 120), ("banner_aerial", g_aerial, 140), ("banner_files", g_folder, 130),
                ("banner_downloader", g_download, 120), ("banner_vpn", g_shield_glass, 120)]:
    glass_tile(n, g, s)
