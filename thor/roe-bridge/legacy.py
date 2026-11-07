"""LOD reader + H3 legacy text table tokenizer (mirrors VCMI CLegacyConfigParser)."""
import struct, zlib

def lod_files(path):
    d = open(path, 'rb').read()
    n, = struct.unpack_from('<I', d, 8)
    out = {}
    for i in range(n):
        e = 92 + i * 32
        nm = d[e:e + 16].split(b'\0')[0].decode('latin1').upper()
        off, size, typ, csize = struct.unpack_from('<IIII', d, e + 16)
        out[nm] = (d, off, size, csize)
    return out

def lod_read(entry):
    d, off, size, csize = entry
    return zlib.decompress(d[off:off + csize]) if csize else d[off:off + size]

def _field_end(b, i):
    """Return index of the terminator of the field starting at i (same rules as VCMI)."""
    n = len(b)
    if i < n and b[i:i+1] == b'"':
        while True:
            i += 1  # skip quote
            while i < n and b[i] not in b'"\t':
                i += 1
            i += 1  # closing quote (or the tab VCMI also treats as end)
            if i < n and b[i:i+1] == b'"':
                continue
            if i < n and b[i] not in b'\t\r':
                while i < n and b[i] not in b'\t\r"':
                    i += 1
                if i >= n or b[i:i+1] != b'"':
                    return i
                continue
            return i
    while i < n and b[i] not in b'\t\r':
        i += 1
    return i

def rows(b):
    """Split table into rows; each row = list of raw field bytes."""
    out, i, n = [], 0, len(b)
    while i < n:
        row = []
        while i < n and b[i:i+1] != b'\n':
            j = _field_end(b, i)
            row.append(b[i:j])
            i = j + 1
        i += 1
        out.append(row)
    return out

def dump(rws):
    return b''.join(b'\t'.join(r) + b'\r\n' for r in rws)


def rows_crlf(b):
    """Split like the original game: records end at CRLF (outside quotes); a bare LF is text.
    Fields are returned unquoted-raw (as stored); use dump_quoted() to write VCMI-safe output."""
    out, row, i, n = [], [], 0, len(b)
    while i < n:
        if b[i:i+1] == b'"':
            j = i + 1
            while j < n:
                if b[j:j+1] == b'"':
                    if b[j+1:j+2] == b'"':
                        j += 2
                        continue
                    break
                j += 1
            j += 1
            while j < n and b[j:j+1] != b'\t' and b[j:j+2] != b'\r\n':
                j += 1
        else:
            j = i
            while j < n and b[j:j+1] != b'\t' and b[j:j+2] != b'\r\n':
                j += 1
        row.append(b[i:j])
        if b[j:j+2] == b'\r\n':
            out.append(row); row = []; i = j + 2
        else:
            i = j + 1
    if row:
        out.append(row)
    return out


def quote_field(f):
    """Quote an unquoted field that contains a bare LF so VCMI's parser keeps it in one record."""
    if b'\n' in f and not f.startswith(b'"'):
        return b'"' + f.replace(b'"', b'""') + b'"'
    return f


def dump_quoted(rws):
    return b''.join(b'\t'.join(quote_field(f) for f in r) + b'\r\n' for r in rws)
