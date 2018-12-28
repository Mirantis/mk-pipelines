/**
 Repo-resolver job which will generate current repo snapshots
 in Reclass format for testing/nightly,proposed versions.

 Output example:

 parameters:
   _param:
      nightly:
        linux_system_repo_mcp_salt_url: http://mirror.mirantis.com/.snapshots/nightly-salt-formulas-xenial-2018-12-21-181741
      testing:
        linux_system_repo_mcp_salt_url: http://mirror.mirantis.com/.snapshots/nightly-salt-formulas-xenial-2018-12-21-181741
      proposed:
        linux_system_repo_mcp_salt_url: http://mirror.mirantis.com/.snapshots/nightly-salt-formulas-xenial-2018-12-21-181741

 * Expected parameters:
   MIRROR_HOST - Mirror host to use to generate snapshots context
*/
common = new com.mirantis.mk.Common()
mirror = new com.mirantis.mk.Mirror()

mirrorHost = env.MIRROR_HOST ?: 'mirror.mirantis.com'
slaveNode = env.SLAVE_NODE ?: 'virtual'

node(slaveNode) {

    def fileName = 'repo-context.yml'
    // TODO: replace with dynamical subsetting from reclass
    def ceph_codename = 'luminous'
    def elasticsearch_version = '5'
    def glusterfs_version_number = '3.8'
    def saltstack_version_number = '2017.7'
    def versions = ['testing', 'proposed', 'nightly']
    def components = [
        'repo_mcp_aptly':'aptly',
        'repo_mcp_cassandra':'cassandra',
        'repo_mcp_ceph': "ceph-${ceph_codename}",
        'repo_mcp_docker_legacy': 'docker-1.x',
        'repo_mcp_docker':'docker',
        'repo_mcp_elasticsearch_curator': 'elasticsearch-curator-5',
        'repo_mcp_elasticsearch': "elasticsearch-${elasticsearch_version}.x",
        'repo_mcp_extra': 'extra',
        'repo_mcp_glusterfs': "glusterfs-${glusterfs_version_number}",
        'repo_mcp_influxdb': 'influxdb',
        'repo_mcp_jenkins': 'jenkins',
        'repo_mcp_maas': 'maas',
        'repo_mcp_percona': 'percona',
        'repo_mcp_saltstack': "saltstack-${saltstack_version_number}",
        'repo_mcp_fluentd_url': 'td-agent',
        'repo_mcp_salt_url': 'salt-formulas',
    ]

    stage('Generate context') {
        def meta = ['_param': [:]]
        versions.each {
            // ubuntu has target.txt in version root
            meta['_param'][it] = ['linux_system_repo_ubuntu_url': mirror.getLatestSnapshotMeta(mirrorHost, it, '', 'ubuntu')['repoUrl'] ]
        }
        components.each { componentKey, componentRepo ->
            for(version in versions) {
                def versionMeta = [:]
                try {
                    versionMeta["linux_system_repo_${componentKey}_url"] = mirror.getLatestSnapshotMeta(mirrorHost, version, componentRepo)['repoUrl']
                } catch(Exception e) {
                    common.errorMsg(e)
                    continue
                }
                meta['_param'][version] << versionMeta
            }
        }

        // remove file if exists
        sh "rm -rf ${fileName}"
        writeYaml file: fileName, data: ['parameters': meta ]
        archiveArtifacts artifacts: fileName
    }
}