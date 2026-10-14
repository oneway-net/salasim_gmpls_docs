# D7 spike

Machine: v1.36.5+k3s1 on Ubuntu 22.04.5 LTS, 1 node(s), 6 cpu, 15.1 GiB
Images: {'pce': 'salasim/pce:spike', 'controller': 'salasim/controller:spike', 'emulator': 'salasim/emulator:spike', 'platform': 'salasim/platform:spike'}

## Loading, per size

| nodes | domains | pods | provision s | load s | reset s | reload s | slowest component |
|---|---|---|---|---|---|---|---|
| 10 | 3 | 19 | 12.9 | 13.3 | 14.9 | 11.0 | linkplane |
| 20 | 3 | 29 | 11.5 | 20.9 | 25.6 | 21.6 | node-10-3-0-7 |
| 30 | 3 | 39 | 7.2 | 33.6 | 33.7 | 36.6 | node-10-3-0-8 |

## Model

load seconds = a + b x nodes: a = 2.4, b = 1.013 per node (r2 = 0.98)

## Memory per pod (MiB, after settling)

- core: 148
- core-db: 75
- emulator: 144
- linkplane: 36
- pce: 177
- pnc: 220

## What to set

Quotas per class (from the memory above, margin 1.3):

- S: `{'pods': '51', 'requests.memory': '7506Mi'}`
- M: `{'pods': '168', 'requests.memory': '24367Mi'}`

Price book allowances (`SALASIM_PRICING`):

- S (30 nodes): expected load 32.8 s -> loadLowSeconds 23, loadHighSeconds 66
- M (120 nodes): expected load 124.0 s -> loadLowSeconds 87, loadHighSeconds 248

## Where the time goes (share of the slowest component, biggest case)

- node-10-3-0-8: 100%
- node-10-3-0-7: 100%
- node-10-3-0-6: 100%
- node-10-3-0-5: 100%
- node-10-3-0-4: 100%
- node-10-3-0-3: 100%
- node-10-3-0-2: 100%
- node-10-3-0-1: 100%
- node-10-2-0-8: 100%
- node-10-2-0-7: 100%
- node-10-2-0-6: 100%
- node-10-2-0-5: 100%
- node-10-2-0-4: 100%
- node-10-2-0-2: 100%
- node-10-2-0-1: 100%
- node-10-1-0-8: 100%
- node-10-1-0-7: 100%
- node-10-1-0-6: 100%
- node-10-1-0-5: 100%
- node-10-1-0-4: 100%
- node-10-1-0-2: 100%
- linkplane: 100%
- node-10-2-0-3: 85%
- node-10-1-0-9: 85%
- node-10-1-0-3: 85%
- node-10-1-0-13: 85%
- node-10-1-0-12: 85%
- pce-d3: 56%
- pnc-d3: 48%
- core: 48%
- pce-d2: 35%
- node-10-1-0-11: 35%
- node-10-1-0-10: 35%
- pnc-d2: 28%
- node-10-1-0-14: 28%
- node-10-1-0-1: 28%
- pnc-d1: 21%
- pce-d1: 14%
- core-db: 1%

## Residue after a reset and after a clean

- (none found)
