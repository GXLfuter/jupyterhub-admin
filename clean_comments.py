import os
import re
import sys

BACKEND_DIR = r"c:\Users\Lonovo\Desktop\jupyterhub\jupyterhub-admin\backend\src\main\java"

HEADER = """/*
 * 作者：nailong
 * 时间：2026/6/12
 */
"""

def remove_comments(content):
    result = []
    i = 0
    n = len(content)
    in_string = False
    string_char = None
    in_line_comment = False
    in_block_comment = False

    while i < n:
        if in_line_comment:
            if content[i] == '\n':
                in_line_comment = False
                result.append(content[i])
            i += 1
            continue

        if in_block_comment:
            if i < n - 1 and content[i] == '*' and content[i+1] == '/':
                in_block_comment = False
                i += 2
            else:
                i += 1
            continue

        if in_string:
            result.append(content[i])
            if content[i] == '\\' and i + 1 < n:
                result.append(content[i+1])
                i += 2
                continue
            if content[i] == string_char:
                in_string = False
            i += 1
            continue

        if i < n - 1 and content[i] == '/' and content[i+1] == '/':
            in_line_comment = True
            i += 2
            continue

        if i < n - 1 and content[i] == '/' and content[i+1] == '*':
            in_block_comment = True
            i += 2
            continue

        if content[i] in ('"', "'"):
            in_string = True
            string_char = content[i]
            result.append(content[i])
            i += 1
            continue

        result.append(content[i])
        i += 1

    return ''.join(result)

def clean_blank_lines(content):
    lines = content.split('\n')
    cleaned = []
    prev_blank = False
    for line in lines:
        stripped = line.strip()
        if stripped == '':
            if not prev_blank:
                cleaned.append('')
            prev_blank = True
        else:
            cleaned.append(line)
            prev_blank = False
    return '\n'.join(cleaned)

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    original = content

    content = remove_comments(content)
    content = clean_blank_lines(content)

    if content.strip().startswith('package'):
        idx = content.index('package')
        before = content[:idx]
        after = content[idx:]
        content = before + HEADER + '\n' + after
    else:
        content = HEADER + '\n' + content

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

    return len(original), len(content)

def main():
    count = 0
    total_orig = 0
    total_new = 0

    for root, dirs, files in os.walk(BACKEND_DIR):
        for file in files:
            if file.endswith('.java'):
                filepath = os.path.join(root, file)
                orig_size, new_size = process_file(filepath)
                count += 1
                total_orig += orig_size
                total_new += new_size
                rel = os.path.relpath(filepath, BACKEND_DIR)
                print(f"  处理: {rel}")

    print(f"\n完成！共处理 {count} 个文件")
    print(f"原始大小: {total_orig/1024:.1f} KB")
    print(f"处理后: {total_new/1024:.1f} KB")
    print(f"减少: {(1 - total_new/total_orig)*100:.1f}%")

if __name__ == '__main__':
    main()
