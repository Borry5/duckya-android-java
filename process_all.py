#!/usr/bin/env python3
"""批量去除用户图片中的 AI 水印。

用法:
    python3 /workspace/process_all.py              # 处理 /workspace/user_images/ 下所有图片
    python3 /workspace/process_all.py --dir /path  # 自定义输入目录
    python3 /workspace/process_all.py --identify   # 仅检测，不修改
"""

import argparse
import sys
from pathlib import Path

from remove_ai_watermarks.identify import identify
from remove_ai_watermarks.metadata import remove_ai_metadata, has_ai_metadata


INPUT_DIR = Path("/workspace/user_images")
OUTPUT_DIR = Path("/workspace/user_images_output")
SUPPORTED = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".HEIC", ".avif"}


def print_header(text: str) -> None:
    bar = "=" * 60
    print(f"\n{bar}\n  {text}\n{bar}")


def do_identify(image_path: Path) -> None:
    print(f"\n🔍 检测: {image_path.name}")
    try:
        report = identify(image_path, check_visible=True, check_invisible=False)
    except Exception as e:
        print(f"   ⚠️  检测失败: {e}")
        return

    print(f"   来源平台: {report.platform or '未检测到（unknown）'}")
    print(f"   是否为 AI 生成相关: {report.is_ai_generated}")
    if report.watermarks:
        print(f"   检测到的标记: {', '.join(report.watermarks)}")
    if report.caveats:
        for c in report.caveats:
            print(f"   ℹ️  {c}")


def do_remove(image_path: Path, output_dir: Path) -> Path | None:
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / image_path.name
    try:
        # 元数据清除（C2PA / EXIF / PNG text chunks / XMP AI 标签）
        out = remove_ai_metadata(image_path, output_path)
        return Path(out)
    except Exception as e:
        print(f"   ⚠️  去除失败: {e}")
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description="批量去除图片 AI 水印")
    parser.add_argument(
        "--dir",
        type=str,
        default=str(INPUT_DIR),
        help=f"输入目录 (默认: {INPUT_DIR})",
    )
    parser.add_argument(
        "--out",
        type=str,
        default=str(OUTPUT_DIR),
        help=f"输出目录 (默认: {OUTPUT_DIR})",
    )
    parser.add_argument(
        "--identify",
        action="store_true",
        help="仅检测，不修改文件",
    )
    args = parser.parse_args()

    in_dir = Path(args.dir)
    out_dir = Path(args.out)

    if not in_dir.exists():
        print(f"❌ 目录不存在: {in_dir}")
        print(f"   请把你的图片放到: {in_dir}")
        return 1

    images = sorted(
        p for p in in_dir.iterdir()
        if p.is_file() and p.suffix.lower() in SUPPORTED
    )

    if not images:
        print(f"📭 目录 {in_dir} 中没有找到支持的图片。")
        print(f"   支持格式: {', '.join(sorted(SUPPORTED))}")
        return 1

    print(f"🎯 找到 {len(images)} 张图片")

    # ---------- 1) 先检测 ----------
    print_header("第 1/2 步：检测每张图片的来源和水印")
    for img in images:
        do_identify(img)

    if args.identify:
        print("\n✅ 检测完成（--identify 模式，未修改任何文件）")
        return 0

    # ---------- 2) 去除元数据 ----------
    print_header("第 2/2 步：去除 AI 元数据 (C2PA / EXIF / XMP)")
    out_dir.mkdir(parents=True, exist_ok=True)
    success_count = 0
    for img in images:
        print(f"\n🛠  处理: {img.name}")
        has_md = has_ai_metadata(img)
        print(f"   处理前检测到 AI 元数据: {has_md}")
        result = do_remove(img, out_dir)
        if result:
            after = has_ai_metadata(result)
            print(f"   处理后 AI 元数据: {after}")
            print(f"   ✅ 输出: {result} ({result.stat().st_size / 1024:.0f} KB)")
            success_count += 1
        else:
            print(f"   ❌ 失败")

    print_header(f"完成！共处理 {success_count}/{len(images)} 张")
    print(f"处理后的文件在: {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
