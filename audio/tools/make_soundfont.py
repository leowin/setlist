#!/usr/bin/env python3
"""Trims a SoundFont 2 file down to a few presets.

Usage: make_soundfont.py IN.sf2 OUT.sf2 [bank:preset ...]
Keeps the given presets, the instruments and samples they use (incl. stereo partners), drops the
optional 24-bit sample chunk. Prints the kept presets and the resulting size.
"""
import struct
import sys


def read_chunks(data, start, end):
    pos = start
    while pos + 8 <= end:
        cid = data[pos:pos + 4]
        size = struct.unpack_from('<I', data, pos + 4)[0]
        yield cid, pos + 8, pos + 8 + size
        pos += 8 + size + (size & 1)


def records(blob, size):
    return [blob[i:i + size] for i in range(0, len(blob), size)]


def main():
    src, dst, *wanted = sys.argv[1:]
    wanted = {tuple(int(x) for x in w.split(':')) for w in wanted} or {(0, 0)}
    data = open(src, 'rb').read()
    assert data[:4] == b'RIFF' and data[8:12] == b'sfbk', 'not a SoundFont'
    lists = {}
    for cid, a, b in read_chunks(data, 12, len(data)):
        assert cid == b'LIST'
        lists[data[a:a + 4]] = (a + 4, b)
    info = data[lists[b'INFO'][0]:lists[b'INFO'][1]]
    sdta = {cid: (a, b) for cid, a, b in read_chunks(data, *lists[b'sdta'])}
    pdta = {cid: data[a:b] for cid, a, b in read_chunks(data, *lists[b'pdta'])}
    smpl = data[sdta[b'smpl'][0]:sdta[b'smpl'][1]]

    phdr = records(pdta[b'phdr'], 38)
    pbag = records(pdta[b'pbag'], 4)
    pmod = records(pdta[b'pmod'], 10)
    pgen = records(pdta[b'pgen'], 4)
    inst = records(pdta[b'inst'], 22)
    ibag = records(pdta[b'ibag'], 4)
    imod = records(pdta[b'imod'], 10)
    igen = records(pdta[b'igen'], 4)
    shdr = records(pdta[b'shdr'], 46)

    def preset_info(r):
        name = r[:20].split(b'\0')[0].decode('latin1')
        preset, bank, bag = struct.unpack_from('<HHH', r, 20)
        return name, preset, bank, bag

    def bag_range(bags, i):
        g0, m0 = struct.unpack_from('<HH', bags[i])
        g1, m1 = struct.unpack_from('<HH', bags[i + 1])
        return range(g0, g1), range(m0, m1)

    keep_presets = [i for i in range(len(phdr) - 1) if preset_info(phdr[i])[2:0:-1] in {(b, p) for b, p in wanted} or (preset_info(phdr[i])[2], preset_info(phdr[i])[1]) in wanted]
    if not keep_presets:
        sys.exit('none of the requested presets found')

    # Instruments used by kept presets, samples used by those instruments (+ stereo partners)
    keep_inst = []
    for i in keep_presets:
        b0 = preset_info(phdr[i])[3]
        b1 = preset_info(phdr[i + 1])[3]
        for b in range(b0, b1):
            gens, _ = bag_range(pbag, b)
            for g in gens:
                op, amount = struct.unpack_from('<HH', pgen[g])
                if op == 41 and amount not in keep_inst:
                    keep_inst.append(amount)
    keep_smpl = []
    for i in keep_inst:
        b0 = struct.unpack_from('<H', inst[i], 20)[0]
        b1 = struct.unpack_from('<H', inst[i + 1], 20)[0]
        for b in range(b0, b1):
            gens, _ = bag_range(ibag, b)
            for g in gens:
                op, amount = struct.unpack_from('<HH', igen[g])
                if op == 53 and amount not in keep_smpl:
                    keep_smpl.append(amount)
    for s in list(keep_smpl):  # stereo partners
        link = struct.unpack_from('<H', shdr[s], 42)[0]
        stype = struct.unpack_from('<H', shdr[s], 40)[0]
        if stype in (2, 4) and link not in keep_smpl and link < len(shdr) - 1:
            keep_smpl.append(link)
    inst_map = {old: new for new, old in enumerate(keep_inst)}
    smpl_map = {old: new for new, old in enumerate(keep_smpl)}

    # Rebuild sample data: each sample followed by 46 zero frames as the spec requires
    new_smpl = bytearray()
    new_shdr = bytearray()
    for old in keep_smpl:
        r = bytearray(shdr[old])
        start, end, loop_start, loop_end = struct.unpack_from('<IIII', r, 20)
        offset = len(new_smpl) // 2
        new_smpl += smpl[start * 2:end * 2] + bytes(92)
        struct.pack_into('<IIII', r, 20, offset, offset + (end - start), offset + (loop_start - start), offset + (loop_end - start))
        link = struct.unpack_from('<H', r, 42)[0]
        struct.pack_into('<H', r, 42, smpl_map.get(link, 0))
        new_shdr += r
    new_shdr += b'EOS'.ljust(46, b'\0')

    def rebuild_zones(items, bags, mods, gens, keep, bag_offset, name_off, fix_gen):
        out_items, out_bags, out_mods, out_gens = bytearray(), bytearray(), bytearray(), bytearray()
        for old in keep:
            r = bytearray(items[old])
            b0 = struct.unpack_from('<H', r, bag_offset)[0]
            b1 = struct.unpack_from('<H', items[old + 1], bag_offset)[0]
            struct.pack_into('<H', r, bag_offset, len(out_bags) // 4)
            out_items += r
            for b in range(b0, b1):
                gr, mr = bag_range(bags, b)
                out_bags += struct.pack('<HH', len(out_gens) // 4, len(out_mods) // 10)
                for g in gr:
                    op, amount = struct.unpack_from('<HH', gens[g])
                    out_gens += struct.pack('<HH', op, fix_gen(op, amount))
                for m in mr:
                    out_mods += mods[m]
        terminal = bytearray(items[-1])
        struct.pack_into('<H', terminal, bag_offset, len(out_bags) // 4)
        out_items += terminal
        out_bags += struct.pack('<HH', len(out_gens) // 4, len(out_mods) // 10)
        return out_items, out_bags, out_mods, out_gens

    n_phdr, n_pbag, n_pmod, n_pgen = rebuild_zones(
        phdr, pbag, pmod, pgen, keep_presets, 24, 0, lambda op, a: inst_map[a] if op == 41 else a)
    n_inst, n_ibag, n_imod, n_igen = rebuild_zones(
        inst, ibag, imod, igen, keep_inst, 20, 0, lambda op, a: smpl_map[a] if op == 53 else a)

    def chunk(cid, body):
        return cid + struct.pack('<I', len(body)) + body + (b'\0' if len(body) & 1 else b'')

    def lst(kind, body):
        return chunk(b'LIST', kind + body)

    pdta_body = b''.join(chunk(c, b) for c, b in [
        (b'phdr', n_phdr), (b'pbag', n_pbag), (b'pmod', n_pmod), (b'pgen', n_pgen),
        (b'inst', n_inst), (b'ibag', n_ibag), (b'imod', n_imod), (b'igen', n_igen), (b'shdr', new_shdr)])
    body = b'sfbk' + lst(b'INFO', info) + lst(b'sdta', chunk(b'smpl', bytes(new_smpl))) + lst(b'pdta', pdta_body)
    out = chunk(b'RIFF', body)
    open(dst, 'wb').write(out)
    for i in keep_presets:
        name, preset, bank, _ = preset_info(phdr[i])
        print(f'kept bank {bank:3d} preset {preset:3d}  {name}')
    print(f'{len(keep_inst)} instruments, {len(keep_smpl)} samples, {len(out) / 1e6:.2f} MB -> {dst}')


if __name__ == '__main__':
    main()
