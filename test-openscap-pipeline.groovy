/**
 *
 * Run openscap xccdf evaluation on given nodes
 *
 * Expected parametes:
 *  SALT_MASTER_URL             Full Salt API address.
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API.
 *
 *  XCCDF_BENCHMARKS_DIR        The XCCDF benchmarks base directory (default /usr/share/xccdf-benchmarks/mirantis/)
 *  XCCDF_BENCHMARKS            List of pairs XCCDF benchmark filename and corresponding profile separated with ','
 *                                  these pairs are separated with semicolon.
 *                                  (e.g. manila/openstack_manila-xccdf.xml,profilename;horizon/openstack_horizon-xccdf.xml,profile)
 *  XCCDF_VERSION               The XCCDF version (default 1.2)
 *  XCCDF_TAILORING_ID          The tailoring id (default None)
 *
 *  TARGET_SERVERS              The target Salt nodes (default *)
 *
 *  ARTIFACTORY_URL             The artifactory URL
 *  ARTIFACTORY_NAMESPACE       The artifactory namespace (default 'mirantis/openscap')
 *  ARTIFACTORY_REPO            The artifactory repo (default 'binary-dev-local')
 *
 *  UPLOAD_TO_DASHBOARD         Boolean. Upload results to the WORP or not
 *  DASHBOARD_API_URL           The WORP api base url. Mandatory if UPLOAD_TO_DASHBOARD is true
 */



/**
  * Upload results to the `WORP` dashboard
  *
  * @param apiUrl               The base dashboard api url
  * @param cloudName            The cloud name (mostly, the given node's domain name)
  * @param nodeName             The node name
  * @param results              The scanning results
  */
def uploadResultToDashboard(apiUrl, cloudName, nodeName, results) {
    // Yes, we do not care of performance and will create at least 4 requests per each result
    def requestData = [:]

    def cloudId
    def nodeId

    // Let's take a look, may be our minion is already presented on the dashboard
    // Get available environments
    environments = common.parseJSON(http.sendHttpGetRequest("${apiUrl}/environment/"))
    for (environment in environments) {
        if (environment['name'] == cloudName) {
            cloudId = environment['uuid']
            break
        }
    }
    // Cloud wasn't presented, let's create it
    if (! cloudId ) {
        // Create cloud
        resuestData['name'] = cloudName
        cloudId = common.parseJSON(http.sendHttpPostRequest("${apiUrl}/environment/", requestData))['env']['uuid']

        // And the node
        // It was done here to reduce count of requests to the api.
        // Because if there was not cloud presented on the dashboard, then the node was not presented as well.
        requestData['nodes'] = [nodeName]
        nodeId = common.parseJSON(http.sendHttpPutRequest("${apiUrl}/environment/${cloudId}/nodes/", requestData))['uuid']
    }

    if (! nodeId ) {
        // Get available nodes in our environment
        nodes = common.parseJSON(http.sendHttpGetRequest("${apiUrl}/environment/${cloudId}/nodes/"))
        for (node in nodes) {
            if (node['name'] == nodeName) {
                nodeId = node['id']
                break
            }
        }
    }

    // Node wasn't presented, let's create it
    if (! nodeId ) {
        // Create node
        requestData['nodes'] = [nodeName]
        nodeId = common.parseJSON(http.sendHttpPutRequest("${apiUrl}/environment/${cloudId}/nodes/", requestData))['uuid']
    }

    // Get report_id
    requestData['env_uuid'] = cloudId
    def reportId = common.parseJSON(http.sendHttpPostRequest("${apiUrl}/reports/openscap/", requestData))['report']['uuid']

    // Upload results
    requestData['results'] = results
    requestData['node_name'] = nodeName
    http.sendHttpPutRequest("${apiUrl}/reports/openscap/${reportId}/", requestData)
}


node('python') {
    def pepperEnv = 'pepperEnv'

    // XCCDF related variables
    def benchmarksAndProfilesArray = XCCDF_BENCHMARKS.tokenize(';')
    def benchmarksDir = XCCDF_BENCHMARKS_DIR ?: '/usr/share/xccdf-benchmarks/mirantis/'
    def xccdfVersion = XCCDF_VERSION ?: '1.2'
    def xccdfTailoringId = XCCDF_TAILORING_ID ?: 'None'
    def targetServers = TARGET_SERVERS ?: '*'

    def salt = new com.mirantis.mk.Salt()
    def python = new com.mirantis.mk.Python()
    def common = new com.mirantis.mk.Common()
    def http = new com.mirantis.mk.Http()

    // To have an ability to work in heavy concurrency conditions
    def scanUUID = UUID.randomUUID().toString()

    def artifactsArchiveName = "openscap-${scanUUID}.zip"
    def resultsBaseDir = "/tmp/openscap/${scanUUID}"
    def artifactsDir = "${env.WORKSPACE}/openscap/${scanUUID}/artifacts"

    def liveMinions

    stage ('Setup virtualenv for Pepper') {
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }

    stage ('Run openscap xccdf evaluation and attempt to upload the results to a dashboard') {
        liveMinions = salt.getMinions(pepperEnv, targetServers)

        if (liveMinions.isEmpty()) {
            throw new Exception('There are no alive minions')
        }

        common.infoMsg("Scan UUID: ${scanUUID}")

        for (minion in liveMinions) {

            // Iterate oscap evaluation over the benchmarks
            for (benchmark in benchmarksAndProfilesArray) {
                def (benchmarkFilePath, profile) = benchmark.tokenize(',').collect({it.trim()})

                // Remove extension from the benchmark name
                def benchmarkPathWithoutExtension = benchmarkFilePath.replaceFirst('[.][^.]+$', '')
                // And build resultsDir based on this path
                def resultsDir = "${resultsBaseDir}/${benchmarkPathWithoutExtension}"

                def benchmarkFile = "${benchmarksDir}${benchmarkFilePath}"

                // Evaluate the benchmark
                salt.runSaltProcessStep(pepperEnv, minion, 'oscap.eval', [
                    'xccdf', benchmarkFile, "results_dir=${resultsDir}",
                    "profile=${profile}", "xccdf_version=${xccdfVersion}",
                    "tailoring_id=${xccdfTailoringId}"
                ])

                // Attempt to upload the scanning results to the dashboard
                if (UPLOAD_TO_DASHBOARD.toBoolean()) {
                    if (common.validInputParam('DASHBOARD_API_URL')) {
                        def cloudName = salt.getGrain(pepperEnv, minion, 'domain')['return'][0].values()[0].values()[0]
                        uploadResultToDashboard(DASHBOARD_API_URL, cloudName, minion, salt.getFileContent(pepperEnv, minion, "${resultsDir}/results.json"))
                    } else {
                        throw new Exception('Uploading to the dashboard is enabled but the DASHBOARD_API_URL was not set')
                    }
                }
            }
        }
    }

/*  // Will be implemented later
    stage ('Attempt to upload results to an artifactory') {
        if (common.validInputParam('ARTIFACTORY_URL')) {
            for (minion in liveMinions) {
                def destDir = "${artifactsDir}/${minion}"
                def archiveName = "openscap-${scanUUID}.tar.gz"
                def tempArchive = "/tmp/${archiveName}"
                def destination = "${destDir}/${archiveName}"

                dir(destDir) {
                    // Archive scanning results on the remote target
                    salt.runSaltProcessStep(pepperEnv, minion, 'archive.tar', ['czf', tempArchive, resultsBaseDir])

                    // Get it content and save it
                    writeFile file: destination, text: salt.getFileContent(pepperEnv, minion, tempArchive)

                    // Remove scanning results and the temp archive on the remote target
                    salt.runSaltProcessStep(pepperEnv, minion, 'file.remove', resultsBaseDir)
                    salt.runSaltProcessStep(pepperEnv, minion, 'file.remove', tempArchive)
                }
            }

            def artifactory = new com.mirantis.mcp.MCPArtifactory()
            def artifactoryName = 'mcp-ci'
            def artifactoryRepo = ARTIFACTORY_REPO ?: 'binary-dev-local'
            def artifactoryNamespace = ARTIFACTORY_NAMESPACE ?: 'mirantis/openscap'
            def artifactoryServer = Artifactory.server(artifactoryName)
            def publishInfo = true
            def buildInfo = Artifactory.newBuildInfo()
            def zipName = "${env.WORKSPACE}/openscap/${scanUUID}/results.zip"

            // Zip scan results
            zip zipFile: zipName, archive: false, dir: artifactsDir

            // Mandatory and additional properties
            def properties = artifactory.getBinaryBuildProperties([
                                "scanUuid=${scanUUID}",
                                "project=openscap"
                            ])

            // Build Artifactory spec object
            def uploadSpec = """{
                "files":
                    [
                        {
                            "pattern": "${zipName}",
                            "target": "${artifactoryRepo}/${artifactoryNamespace}/openscap",
                            "props": "${properties}"
                        }
                    ]
                }"""

            // Upload artifacts to the given Artifactory
            artifactory.uploadBinariesToArtifactory(artifactoryServer, buildInfo, uploadSpec, publishInfo)

        } else {
            common.warningMsg('ARTIFACTORY_URL was not given, skip uploading to artifactory')
        }
    }
*/

}
