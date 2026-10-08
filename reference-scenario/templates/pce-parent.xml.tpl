<?xml version="1.0" encoding="UTF-8"?>
<config>
    <ParentPCEServerPort>__PARENT_PCE_SERVER_PORT__</ParentPCEServerPort>
    <parentPCEManagementPort>__PARENT_PCE_MANAGEMENT_PORT__</parentPCEManagementPort>
    <ParentPCEServerAddress>__PARENT_PCE_ADDRESS__</ParentPCEServerAddress>
    <PCEServerLogFile>ParentPCEServer.log</PCEServerLogFile>
    <PCEPParserLogFile>ParentPCEPParser.log</PCEPParserLogFile>
    <totalTopologuNums>__TOTAL_TOPOLOGY_NUMS__</totalTopologuNums>
    <multiDomain>true</multiDomain>
    <readMDTEDFromFile>false</readMDTEDFromFile>
    <actingAsBGP4Peer>false</actingAsBGP4Peer>
    <stateful>true</stateful>
    <lspUpdate>true</lspUpdate>
    <ChildPCERequestsProcessors>2</ChildPCERequestsProcessors>
    <layer type="mpls" default="true"></layer>
    <algorithmRule of="1003" svec="false" name="MDHPCEMinNumberDomainsKSPAlgorithm" isParentPCEAlgorithm="true" isSSONAlgorithm="false"/>
</config>
