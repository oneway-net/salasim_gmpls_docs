# simrun-bce47d36b88d：完整复用通过，风险隔离未通过

证据：169 `/home/wings/salasim_gmpls/.salasim/evidence/20260911-reusebce4-audit`，运行目录`20260911-protected-reuse-r3`。

20片完成，18服务36绑定；终态12 UP、6 STOPPED、0 DEGRADED/DOWN。6个重建业务在驱动检查时均主备ACTIVE，qualified/protectionReady=true；1次真实释放IDLE/命中/信令绕过及对应1个端到端样本，跨结果边界0→2。此为有限样本功能通过，不是全面性能或保护风险通过。

统计管道和Backend压力审计通过。信令审计仍报slice-boundary-signaling-not-drained和incomplete-terminal-active-path-metrics。完整多源对账最终失败shared-srlg-in-protection-pairs。

## 新的确认问题

重建业务`svc-d73b292987e946b1a063ad2903353176`（reuse-cycle-0005），primary `tun-90953716192a4d5a87cd3e6bbf857684`，standby `tun-5937c42ffe07487b93c6bc78817c0b33`，终态共享8条链路、8个SRLG，但两端protectionDiversity.degraded=false，业务UP。两份独立审计均确认未标记共享风险，不可忽略。最近主路径预计算替换沿用旧物理业务symbolic，片18完成；实际成因仍需追踪保护组归属与引用。

候选原因：ParentMdLspReroute调用ProtectionGroupRegistry时仍使用MD_LSP物理spn，而保护组按当前业务逻辑spn登记。MultiDomainLSPDB身份解析修复不覆盖这个独立的保护组索引。需要核实并统一当前业务保护约束、队列归属和风险更新；不能直接让所有旧业务删除/迟到事件重定向新业务，这可能引入串扰。

## 前端

`/tmp/reusebce4-browser.json`：四板块、20片、18/36及Mission正常，统计质量QUALIFIED；详情lineage展开仍未通过。改用真实CDP鼠标输入再测`/tmp/reusebce4-input-browser.json`仍失败，试验性脚本改动已撤回，没有宣称修好。无console/network errors不能证明交互无误。

## 未完成

保护组复用归属修复、IDLE恢复真实覆盖、缺失指标、片边界积压、旧轮容量失败时刻回放，以及审计脚本STOPPED口径。所有历史保持原样，不补零、不伪造。后续先保存现场并正常stop核验22PCE资源，再按明确根因修复和重测。
