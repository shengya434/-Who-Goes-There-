#!/usr/bin/env python3
# 手写 16x16 PNG（只用 zlib+struct，不依赖 PIL）
import zlib, struct, os

W = H = 16

def write_png(path, px):
    raw = bytearray()
    for y in range(H):
        raw.append(0)  # filter type 0
        for x in range(W):
            r, g, b, a = px[y][x]
            raw += bytes((r, g, b, a))
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    ihdr = struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)  # 8-bit RGBA
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) \
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)

def blank():
    return [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]

def rect(px, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if 0 <= x < W and 0 <= y < H:
                px[y][x] = color

def put(px, x, y, color):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = color

def outline(px, color):
    """给不透明区域描一圈边（把贴着透明邻居的像素染成 color）。"""
    marks = []
    for y in range(H):
        for x in range(W):
            if px[y][x][3] == 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if not (0 <= nx < W and 0 <= ny < H) or px[ny][nx][3] == 0:
                    marks.append((x, y))
                    break
    for (x, y) in marks:
        px[y][x] = color

# ---------------- 印章：红方印 + 白色印纹 ----------------
stamp = blank()
RED     = (198, 34, 42, 255)
RED_HI  = (232, 76, 84, 255)
RED_LO  = (146, 18, 26, 255)
WHITE   = (250, 246, 240, 255)

rect(stamp, 2, 2, 13, 13, RED)
rect(stamp, 2, 2, 13, 2, RED_HI)   # 上受光
rect(stamp, 2, 2, 2, 13, RED_HI)   # 左受光
rect(stamp, 2, 13, 13, 13, RED_LO) # 下阴影
rect(stamp, 13, 2, 13, 13, RED_LO) # 右阴影
for (cx, cy) in ((2, 2), (13, 2), (2, 13), (13, 13)):
    put(stamp, cx, cy, (0, 0, 0, 0))  # 圆角
# 白色印纹：十字 + 四角点
rect(stamp, 5, 7, 10, 8, WHITE)
rect(stamp, 7, 5, 8, 10, WHITE)
for (cx, cy) in ((5, 5), (10, 5), (5, 10), (10, 10)):
    put(stamp, cx, cy, WHITE)

# ---------------- 橡皮擦：浅粉方块 + 斜角 ----------------
eraser = blank()
PINK    = (243, 168, 188, 255)
PINK_HI = (252, 208, 218, 255)
PINK_LO = (198, 118, 142, 255)
EDGE    = (170, 85, 110, 255)

rect(eraser, 3, 5, 12, 12, PINK)
rect(eraser, 3, 5, 12, 6, PINK_HI)   # 顶面
rect(eraser, 3, 12, 12, 12, PINK_LO) # 底面
rect(eraser, 3, 5, 3, 12, PINK_HI)   # 左侧
rect(eraser, 12, 5, 12, 12, PINK_LO) # 右侧
# 斜角：削掉右上角
put(eraser, 12, 5, (0, 0, 0, 0))
put(eraser, 12, 6, (0, 0, 0, 0))
put(eraser, 11, 5, (0, 0, 0, 0))
outline(eraser, EDGE)

out_dir = os.environ["OUT_DIR"]
write_png(os.path.join(out_dir, "stamp.png"), stamp)
write_png(os.path.join(out_dir, "eraser.png"), eraser)
print("written to", out_dir)
