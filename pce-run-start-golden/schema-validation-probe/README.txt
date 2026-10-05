Experiment 2026-10-05: what ODL's NETCONF server stack (netconf-server 11.0.0, same as salasim_gmpls_netconf)
does with an invalid salasim-pce-run:prepare-run input. Run in process (the sandbox forbids socket binds),
through NetconfOperationRouter.onNetconfMessage, i.e. the real RuntimeRpc XML parsing.
Built as a scratch copy of yang/tools/netconf-server-probe (not committed there): the copy adds our three modules to
YangModels.REPO_FILES, registers a no-op prepare-run/commit-clock/reset-run in EmbeddedNetconfServer, and adds
newLocalRouter(). ValidationProbe.java is the driver; results.txt the output.
