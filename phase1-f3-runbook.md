# Phase 1 F3 — 169 验收运行手册（准备稿，未执行）

状态：**仅准备**。本文档不授权任何 push / 部署 / 访问 169。每个 §2 的决定项由用户拍板，且只有用户明确说"部署"之后才能执行 §4 起的操作。
只读状态脚本：`tools/phase1-repo-status.sh`（不连任何远端）。

## 1. 现状（2026-10-06，`tools/phase1-repo-status.sh`）

| 仓库 | HEAD | 相对 origin/k8s-deploy | 备注 |
|---|---|---|---|
| backend | a5ed764 | +850 | 另有 origin/dev +33 |
| pce | 0d3c4d0 | +445 | origin/dev +49 |
| emulator | 69c075c | +47 | origin/dev +16 |
| frontend | 5a64f7a | +216 | Phase 1 未改 |
| protocols / topology | 77c0673 / 8038ad1 | +17 / +32 | |
| yang / netconf / controller | 9dd68f3 / c63b810 / cfc3c9f | **无远端** | 服务器拉不到 |

全部在 `framework-enhancement` 分支，无 upstream。服务器的构建脚本（`scripts/multi-user/update-and-deploy.sh`、`update-cluster-and-test.sh`、`smart-update-cluster.sh`）的 REPOS 数组含 yang/netconf，**不含 controller**；`build-registry-images-host.sh --components controller` 可构建 controller 镜像（`salasim_gmpls_controller/Dockerfile`，需要 workspace 里有 yang）。

## 2. 需要用户决定（阻塞项）

1. **远端**：yang、netconf、controller（以及 docs）没有远端。服务器靠 git 同步（不用 SCP），因此要先为这三个仓库建远端并在服务器上 clone 到 workspace。仓库地址/托管位置由用户定。
2. **分支**：`framework-enhancement` 相对 k8s-deploy 多数百个提交（含大量 Phase 1 之外的工作）。选项：(a) 整个分支作为部署基线（会连带上线其他未部署的改动：profile 拆分、MPLS 统一、跨快照路由等）；(b) 仅 Phase 1 切一条分支。建议 (a) 前先确认这些改动均可上 169。
3. **update 脚本是否纳入 controller**：把 `salasim_gmpls_controller` 加进 REPOS 数组并让镜像构建在 yang/netconf/controller 变化时重建 controller，否则要手动 `--components controller`。
4. **基线 run**：选一个 Phase 1.1 之前、同 seed 的已有 run 作为对比对象（指标：启动耗时、`windowLagEvents`、封存、故障 run 受影响 LSP 集合）。
5. **O13 资源**：每个 PCE pod 现在多一个 controller sidecar，沿用原 requests/limits，需确认 169 的容量。

## 3. 部署前本地检查（可随时做，无副作用）

- [ ] 各仓库 `git status` 干净（脚本）。
- [ ] backend `pytest`：预期 2243 通过，仅 `test_update_cluster_and_test_script`×2 基线失败。
- [ ] controller `mvn -q clean verify`；netconf `mvn -q clean install`；yang `./validate.sh && mvn -q clean install`。
- [ ] PCE `rm -rf target`（含所有 target/）后 `mvn -q clean test`；沙箱 socket 类 21 个错误为基线。
- [ ] `python scripts/...` 编译一份 7 节点 manifest，`kubectl kustomize` 检查 sidecar、`salasim-mdsc` Service、`mpi` 端口、凭据卷 `items` 层级。

## 4. 部署顺序（获批后；已知坑）

1. 在 169 上 git pull 全部仓库到选定提交（含新远端的 yang/netconf/controller）。**不 SCP**。
2. 确认没有进行中的 run；**不要重启 backend**（有 run 时，改走离线 DB 补丁）。
3. 清所有 `target/`，`mvn clean`，重建 PCE（`generate-autojar-PCE` / `-ParentPCE`）、emulator、controller 镜像；镜像名 `salasim/pce`、`salasim/emulator`、`salasim/controller`，推到本地 registry 32000。
4. `kubectl set image` 用 **digest**（tag 缓存），用 `javap` 抽查新类确实在镜像里。
5. 部署 PCE 后 **rollout-restart emulator StatefulSet**（PCC 不自动重连）。
6. 部署 backend（无 run 时），确认 `/api/v1/sim/wall-clock` 可读、MDSC 可达（`salasim-mdsc:8181`）。
7. 起 7 节点部署，确认 MDSC 的 `managed-devices` 全部 connected，各 PNC 的节点都 connected（E2 未验证项）。

## 5. 验收用例

| # | 用例 | 通过标准 |
|---|---|---|
| A1 | 正常 run，seed S，两次 | 启动成功；`windowLagEvents == 0`；封存完成；两次结果一致 |
| A2 | 与基线 run（同 seed）比启动耗时 | prepare 屏障 < 30 s（超过先问用户，§13 #9）；记录实测 prepare 耗时（C3 未量） |
| A3 | 带故障 run（同 seed） | 受影响 LSP 集合与基线一致；故障由 MountFaultWriter 经 PNC/节点下发，收据（`/sim/faults/receipts`）齐全 |
| A4 | 强制一个 PNC prepare 失败 | 全部回滚：其余 PNC/PCE reset，run 起不来且无残留；错误信息指明失败 PNC |
| A5 | prepare 与 commit 之间重启 MDSC | 结果必须是明确的：要么整体失败并回滚，要么 commit 成功且故障计划不丢。**风险**：MDSC 内存里的 PlannedFaults 重启后丢失——先本地在 `ChainWorld` 加 `restartMdsc` 场景确认行为再上 169 |
| A6 | pause/resume | 节点 resume 走 fleet start `resume=true`，不回滚时钟 |

### 失败注入做法（建议，需批准）

- A4：对一个域的 PNC sidecar 设错误的库存（或把该 PNC 容器的 inventory 文件指到不可达的 device），或临时 `kubectl exec` 杀该 PNC 进程后立即触发 start。**不要**改生产设置文件；用一个一次性的测试部署。
- A5：start-run 的 prepare 应答后、commit 前 `kubectl delete pod` parent pod（MDSC 与 Parent PCE 同 pod）——这同时重启 Parent PCE，需区分两者的影响；单独只重启 MDSC 容器用 `kubectl exec <pod> -c controller -- kill 1`。

## 6. 回滚

- 镜像：`kubectl set image` 回到上一 digest（部署前记录每个 workload 当前 digest）。
- 产物：旧 run 的 artifact 不兼容新 Parent（不再有 networkDescriptionFile/topologyScheduleFile），回滚必须连 backend + compiler 一起回滚，旧 deployment 需重新编译。
- 数据库：Phase 1 未加迁移；回滚不需要 DB 改动。

## 7. 部署前仍未验证的项（来自 E2/F2）

真实 sidecar 启动；managed-devices 全部 connected；MountFaultWriter 对真实 emulator；O13 资源；Parent PCE 以空 TED 启动；本地 7 节点 run；prepare 的真实耗时。

## 8. 报告格式

每个用例记录：命令/时间戳、run id、关键指标、与基线差值、失败时的完整输出。结果追加到 `phase1-implementation-design.md` §14 的 F3 行。
