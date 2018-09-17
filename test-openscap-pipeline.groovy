node('python') {
    def splitChar = ','
    def pepperEnv = 'pepperEnv'
    def benchmarks = BENCHMARKS.split(splitChar)
    def profile = PROFILE
    def targetServers = TARGET_SERVERS ?: '*'
    def dashboardUri = DASHBOARD_URI ?: 'http://127.0.0.1:12345/openscap'

    def salt = new com.mirantis.mk.Salt()
    def openscap = new com.mirantis.mk.Openscap()

    def liveMinions = salt.GetMinions(pepperEnv, targetServers)

    if (liveMinions.isEmpty()) {
        throw new Exception('There is no alive minions')
    }

    def artifactsDir = '/tmp/openscap'
    def benchmarkBaseDir = '/tmp/openscap-'

    stage ('Run openscap xccdf evaluation') {
        for (int m = 0; m < liveMinions.size(); m++) {
            def target = liveMinions[i]
            def cloudName = salt.getGrain(pepperEnv, target, 'domain')['return'][0].values()[0].values()[0]
            sh "mkdir -p ${artifactsDir}"
            for (int b = 0; b < benchmarks.size(); b++) {
                def resultsDir = "${benchmarkBaseDir}${benchmarks[b]}"
                openscap.openscapEval(pepperEnv, target, 'xccdf', bench, results_dir = "${resultsDir}", profile = profile)
                if (UPLOAD_TO_DASHBOARD.toBoolean() == true) {
                    def _artDir = "${artifactsDir}/${target}/${benchmarks[b]}"
                    sh "mkdir -p ${_artDir}"
                    openscap.copyResultXml(pepperEnv, target, "${resultsDir}/results.xml", "${_artDir}/results.xml")
                    openscap.uploadScanResultsToDashboard(dashboardUri, "${_artDir}/results.xml", cloud_name, target)
                }
            }
        }
    }

    stage ('Archive artifacts') {
        for (int m = 0; m < liveMinions.size(); m++) {
            def target = liveMinions[i]
            for (int b = 0; b < benchmarks.size(); b++) {
                def dest = "${artifactsDir}/${target}/${benchmarks[b]}"
                openscap.archiveScanResults(pepperEnv, target, "${dest}", "${benchmarkBaseDir}${benchmarks[b]}")
            }
        }
        openscap.archiveOpenscapArtifacts(artifactsDir)
    }
}
