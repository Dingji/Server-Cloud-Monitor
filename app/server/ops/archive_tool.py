#!/usr/bin/env python3
"""Archive inner-file tool for the panel file manager (ops-server/files.js).

Modes (argv, output on stdout):
  list    ARCHIVE                     -> JSON {ok, kind, entries:[{name,isDir,size,mtimeMs,isLink}], count, truncated}
  preview ARCHIVE ENTRY [--b64]       -> JSON {ok, binary, truncated, text, size, base64?, mime}
  cat     ARCHIVE ENTRY               -> raw member bytes on stdout (binary safe)
  zip     OUT SRC...                  -> create zip (fallback when the zip binary is absent)

Entries are read straight out of the archive and never written to disk, so a
hostile entry name (../, absolute paths) can never escape the target file.
Errors come back as {"ok": false, "error": "..."} on stdout with exit code 0 so
the Node side can surface a readable message instead of a stack trace.
"""
import sys
import json
import base64
import zipfile
import tarfile
import os


def zip_create(out_path, sources):
    """zip 二进制缺失时的回退：与 `cd dirname && zip -qr out basename` 保持同样的
    布局（文件用 basename，目录保留顶层名），目录递归、软链接按目标文件收。"""
    parent = os.path.dirname(out_path) or '.'
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for src in sources:
            if os.path.isdir(src) and not os.path.islink(src):
                top = os.path.basename(src.rstrip('/')) or src
                for root, _dirs, fs in os.walk(src):
                    for f in fs:
                        fp = os.path.join(root, f)
                        z.write(fp, os.path.join(top, os.path.relpath(fp, src)))
            elif os.path.exists(src):
                z.write(src, os.path.basename(src))
            else:
                raise OSError('源不存在：' + src)
    return {'ok': True, 'path': out_path, 'bytes': os.path.getsize(out_path), 'sources': len(sources)}

MAX_LIST = 20000
PREVIEW_BYTES = 64 * 1024
B64_MAX = 8 * 1024 * 1024

MIME = {
    '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
    '.webp': 'image/webp', '.svg': 'image/svg+xml', '.bmp': 'image/bmp', '.ico': 'image/x-icon',
    '.avif': 'image/avif', '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime',
    '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.m4a': 'audio/mp4',
    '.flac': 'audio/flac', '.pdf': 'application/pdf', '.txt': 'text/plain',
    '.json': 'application/json', '.md': 'text/markdown', '.html': 'text/html', '.css': 'text/css',
    '.js': 'text/javascript', '.xml': 'application/xml', '.csv': 'text/csv',
}


def emit(obj):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    print(json.dumps(obj, ensure_ascii=False))


def fail(msg, to_stderr=False):
    """cat 模式的错误必须走 stderr + 非零退出码：stdout 在那个模式下就是文件字节流，
    错误 JSON 混进去会把坏字节当成下载内容。"""
    if to_stderr:
        sys.stderr.write(json.dumps({'ok': False, 'error': msg}, ensure_ascii=False))
        sys.exit(1)
    emit({'ok': False, 'error': msg})
    sys.exit(0)


def detect_kind(archive):
    lower = archive.lower()
    if lower.endswith('.zip'):
        return 'zip'
    for suffix in ('.tar.gz', '.tgz', '.tar.bz2', '.tbz2', '.tar.xz', '.txz', '.tar'):
        if lower.endswith(suffix):
            return 'tar'
    return None


def open_archive(archive, kind):
    if kind == 'zip':
        return zipfile.ZipFile(archive), None
    return tarfile.open(archive, mode='r:*'), 'tar'


def find_member(handle, kind, entry):
    """Exact name first, then the './'-prefixed form tar usually stores."""
    if kind == 'tar':
        try:
            m = handle.getmember(entry)
            return m
        except KeyError:
            pass
        for alt in ('./' + entry, '/' + entry):
            try:
                return handle.getmember(alt)
            except KeyError:
                pass
        raise KeyError(entry)
    names = set(handle.namelist())
    for cand in (entry, './' + entry):
        if cand in names:
            return cand
    raise KeyError(entry)


def open_member(handle, kind, member):
    if kind == 'tar':
        if member.isdir():
            return None
        return handle.extractfile(member)
    info = handle.getinfo(member)
    if info.is_dir():
        return None
    return handle.open(member)


def member_size(handle, kind, member):
    if kind == 'tar':
        return member.size
    return handle.getinfo(member).file_size


def main():
    args = sys.argv[1:]
    if len(args) < 2:
        fail('用法：archive_tool.py list|preview|cat|zip ARCHIVE [ENTRY|SRC...] [--b64]')
        return
    mode, archive = args[0], args[1]
    if mode == 'zip':
        sources = args[2:]
        if not sources:
            fail('zip 模式需要至少一个源路径', to_stderr=True)
            return
        try:
            emit(zip_create(archive, sources))
        except (OSError, zipfile.BadZipFile, tarfile.TarError) as e:
            # 与 cat 同理走 stderr + 非零退出码，别让调用方把半成品当成功
            try:
                if os.path.exists(archive):
                    os.unlink(archive)
            except OSError:
                pass
            fail('打包失败：' + str(e), to_stderr=True)
        return
    want_b64 = '--b64' in args[3:]
    kind = detect_kind(archive)
    if kind is None:
        fail('暂不支持的压缩格式（支持 zip / tar / tar.gz / tar.bz2 / tar.xz）')
        return
    try:
        handle, inner = open_archive(archive, kind)
    except zipfile.BadZipFile:
        fail('不是有效的 zip 压缩包（或已损坏）')
        return
    except tarfile.ReadError:
        fail('不是有效的 tar 压缩包（或已损坏）')
        return
    except (OSError, PermissionError) as e:
        fail('无法读取压缩包：' + str(e))
        return

    try:
        if mode == 'list':
            entries = []
            if inner == 'tar':
                members = handle.getmembers()
                truncated = len(members) > MAX_LIST
                for m in members[:MAX_LIST]:
                    entries.append({
                        'name': m.name, 'isDir': m.isdir(), 'isLink': m.issym() or m.islnk(),
                        'size': 0 if m.isdir() else m.size, 'mtimeMs': int(m.mtime * 1000),
                    })
            else:
                infos = handle.infolist()
                truncated = len(infos) > MAX_LIST
                for info in infos[:MAX_LIST]:
                    dt = info.date_time
                    mtimeMs = 0
                    if dt and dt[0] > 1980:
                        import calendar
                        try:
                            mtimeMs = int(calendar.timegm(dt + (0, 0, 0)) * 1000)
                        except Exception:
                            mtimeMs = 0
                    entries.append({
                        'name': info.filename, 'isDir': info.is_dir(), 'isLink': False,
                        'size': 0 if info.is_dir() else info.file_size, 'mtimeMs': mtimeMs,
                    })
            emit({'ok': True, 'kind': kind, 'entries': entries,
                  'count': len(entries), 'truncated': truncated})
            return

        entry = args[2] if len(args) > 2 else ''
        if not entry:
            fail('缺少条目名')
            return
        if any(ord(c) < 32 for c in entry) or len(entry) > 4000:
            fail('条目名非法')
            return
        member = find_member(handle, inner, entry)
        if mode == 'preview':
            is_dir = member.isdir() if inner == 'tar' else handle.getinfo(member).is_dir()
            if is_dir:
                fail('这是压缩包内的目录，请继续进入浏览')
                return
            stream = open_member(handle, inner, member)
            if stream is None:
                fail('该条目不可读（可能是链接或特殊成员）')
                return
            size = member_size(handle, inner, member)
            data = stream.read(PREVIEW_BYTES + 1)
            truncated = len(data) > PREVIEW_BYTES
            head = data[:PREVIEW_BYTES]
            binary = b'\x00' in head
            import os
            out = {'ok': True, 'name': entry, 'size': size, 'binary': binary,
                   'truncated': truncated,
                   'mime': MIME.get(os.path.splitext(entry)[1].lower(), '')}
            if binary and want_b64 and size <= B64_MAX:
                stream2 = open_member(handle, inner, member)
                out['base64'] = base64.b64encode(stream2.read()).decode('ascii')
            elif not binary:
                out['text'] = head.decode('utf-8', errors='replace')
            emit(out)
            return

        if mode == 'cat':
            try:
                stream = open_member(handle, inner, member)
            except (KeyError, RuntimeError, OSError, zipfile.BadZipFile, tarfile.TarError) as e:
                fail('条目不可读：' + str(e), to_stderr=True)
            if stream is None:
                fail('该条目不可读（目录或特殊成员）', to_stderr=True)
            out = sys.stdout.buffer
            while True:
                chunk = stream.read(256 * 1024)
                if not chunk:
                    break
                out.write(chunk)
            out.flush()
            return

        fail('未知模式：' + mode)
    except KeyError as e:
        missing = str(e.args[0]) if e.args else (args[2] if len(args) > 2 else '')
        fail('压缩包内没有这个条目：' + missing, to_stderr=(mode == 'cat'))
    except RuntimeError as e:
        if 'encrypted' in str(e).lower() or 'password' in str(e).lower():
            fail('压缩包已加密，暂不支持在线预览', to_stderr=(mode == 'cat'))
        else:
            fail('读取失败：' + str(e), to_stderr=(mode == 'cat'))
    except (OSError, zipfile.BadZipFile, tarfile.TarError) as e:
        fail('读取失败：' + str(e), to_stderr=(mode == 'cat'))


if __name__ == '__main__':
    main()
