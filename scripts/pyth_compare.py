#!/usr/bin/env python3
import urllib.request, json, time
from datetime import datetime, timezone

PYTH = '0xe62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43'

def pp(ts):
    url = f'https://hermes.pyth.network/v2/updates/price/{ts}?ids[]={PYTH}'
    r = urllib.request.urlopen(
        urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=20)
    d = json.loads(r.read())['parsed'][0]
    return int(d['price']['price']) * (10 ** d['price']['expo'])

with open('/tmp/pyth_samples.json') as f:
    samples = json.load(f)

m = 0; x = 0
results = []

for i, s in enumerate(samples):
    ts = s['ts']
    try:
        po = pp(ts)
        time.sleep(0.3)
        pc = pp(ts + 300)
        time.sleep(0.3)
        pd = 'UP' if pc > po else 'DN'
        ok = pd == s['bd']
        if ok: m += 1
        else: x += 1
        sym = '✅' if ok else '❌'
        dt = datetime.fromtimestamp(ts, tz=timezone.utc)
        line = (f'{sym} {dt.strftime("%m/%d %H:%M")} '
                f'Pyth:{((pc-po)/po)*100:+.5f}%={pd} '
                f'Bin:{((s["bc"]-s["bo"])/s["bo"])*100:+.5f}%={s["bd"]} '
                f'|{s["bf"]:.4f}%| gap=${po-s["bo"]:+.0f}')
        print(line, flush=True)
        results.append(line)
    except Exception as e:
        print(f'ERR {i}: {e}', flush=True)

t = m + x
print(f'\n=== 결과 ===', flush=True)
print(f'{t}개 중 {m}개 일치 ({m/t*100:.1f}%)', flush=True)
print(f'이전 10개 합산: {m+10}/{t+10} ({(m+10)/(t+10)*100:.1f}%)', flush=True)

# 파일로도 저장
with open('/tmp/pyth_result.txt', 'w') as f:
    for r in results:
        f.write(r + '\n')
    f.write(f'\n{t}개 중 {m}개 일치 ({m/t*100:.1f}%)\n')
    f.write(f'이전 10개 합산: {m+10}/{t+10} ({(m+10)/(t+10)*100:.1f}%)\n')
print('결과 저장: /tmp/pyth_result.txt', flush=True)
