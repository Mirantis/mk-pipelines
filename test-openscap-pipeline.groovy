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
  * @param reportType           Type of the report to create/use, either 'openscap' or 'cve'
  * @param reportId             Report Id to re-use, if empty report will be created
  * @param results              The scanning results as a json file content (string)
  * @return reportId            The Id of the report created if incoming reportId was empty, otherwise incoming reportId
  */
def uploadResultToDashboard(apiUrl, cloudName, nodeName, reportType, reportId, results) {
    def common = new com.mirantis.mk.Common()
    def http = new com.mirantis.mk.Http()

    // Yes, we do not care of performance and will create at least 4 requests per each result
    def requestData = [:]

    def cloudId
    def nodeId

    def worpApi = [:]
    worpApi["url"] = apiUrl

    // Let's take a look, may be our minion is already presented on the dashboard
    // Get available environments
    common.infoMsg("Making GET to ${worpApi.url}/environment/")
    environments = http.restGet(worpApi, "/environment/")
    for (environment in environments) {
        if (environment['name'] == cloudName) {
            cloudId = environment['uuid']
            break
        }
    }
    // Cloud wasn't presented, let's create it
    if (! cloudId ) {
        // Create cloud
        requestData = [:]
        requestData['name'] = cloudName
        common.infoMsg("Making POST to ${worpApi.url}/environment/ with ${requestData}")
        cloudId = http.restPost(worpApi, "/environment/", requestData)['env']['uuid']

        // And the node
        // It was done here to reduce count of requests to the api.
        // Because if there was not cloud presented on the dashboard, then the node was not presented as well.
        requestData = [:]
        requestData['nodes'] = [nodeName]
        common.infoMsg("Making PUT to ${worpApi.url}/environment/${cloudId}/nodes/ with ${requestData}")
        nodeId = http.restCall(worpApi, "/environment/${cloudId}/nodes/", "PUT", requestData)['uuid']
    }

    if (! nodeId ) {
        // Get available nodes in our environment
        common.infoMsg("Making GET to ${worpApi.url}/environment/${cloudId}/nodes/")
        nodes = http.restGet(worpApi, "/environment/${cloudId}/nodes/")
        for (node in nodes) {
            if (node['name'] == nodeName) {
                nodeId = node['uuid']
                break
            }
        }
    }

    // Node wasn't presented, let's create it
    if (! nodeId ) {
        // Create node
        requestData = [:]
        requestData['nodes'] = [nodeName]
        common.infoMsg("Making PUT to ${worpApi.url}/environment/${cloudId}/nodes/ with ${requestData}")
        nodeId = http.restCall(worpApi, "/environment/${cloudId}/nodes/", "PUT", requestData)['uuid']
    }

    // Create report if needed
    if (! reportId ) {
        requestData = [:]
        requestData['env_uuid'] = cloudId
        common.infoMsg("Making POST to ${worpApi.url}/reports/${reportType}/ with ${requestData}")
        reportId = http.restPost(worpApi, "/reports/${reportType}/", requestData)['report']['uuid']
    }

    // Upload results
    // NOTE(pas-ha) results should already be a dict with 'results' key
    requestData = common.parseJSON(results)
    requestData['node_name'] = nodeName
    common.infoMsg("First result in results to PUT is ${requestData['results'][0]}")
    // NOTE(pas-ha) not logging whole results to be sent, is too large and just spams the logs
    common.infoMsg("Making PUT to ${worpApi.url}/reports/${reportType}/${reportId}/ with node name ${requestData['node_name']} and results")
    http.restCall(worpApi, "/reports/${reportType}/${reportId}/", "PUT", requestData)
    return reportId
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
    def resultsBaseDir = "/var/log/openscap/${scanUUID}"
    def artifactsDir = "openscap"

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

        // Clean all results before proceeding with results from every minion
        dir(artifactsDir) {
            deleteDir()
        }

        def reportId
        // Iterate oscap evaluation over the benchmarks
        for (benchmark in benchmarksAndProfilesArray) {
            def (benchmarkFilePath, profile) = benchmark.tokenize(',').collect({it.trim()})

            // Remove extension from the benchmark name
            def benchmarkPathWithoutExtension = benchmarkFilePath.replaceFirst('[.][^.]+$', '')

            // Get benchmark name
            def benchmarkName = benchmarkPathWithoutExtension.tokenize('/')[-1]

            // And build resultsDir based on this path
            def resultsDir = "${resultsBaseDir}/${benchmarkPathWithoutExtension}"

            def benchmarkFile = "${benchmarksDir}${benchmarkFilePath}"

            // Evaluate the benchmark on all minions at once
            salt.runSaltProcessStep(pepperEnv, targetServers, 'oscap.eval', [
                'xccdf', benchmarkFile, "results_dir=${resultsDir}",
                "profile=${profile}", "xccdf_version=${xccdfVersion}",
                "tailoring_id=${xccdfTailoringId}"
            ])

            // fetch, store and publish results one by one
            for (minion in liveMinions) {

                def nodeShortName = minion.tokenize('.')[0]
                def archiveName = "${scanUUID}_${nodeShortName}_${benchmarkName}.tar"
                def localResultsDir = "${artifactsDir}/${scanUUID}/${nodeShortName}"

                // TODO(pas-ha) when using Salt >= 2017.7.0, use file.read(path) module
                // also investigate compressing to gz/xz and reading with binary=True (for less traffic)
                salt.cmdRun(pepperEnv, minion, "tar -cf /tmp/${archiveName} -C ${resultsBaseDir} .")
                // NOTE(pas-ha) salt.getFileContent does not pass extra args to cmdRun that we need
                fileContents = salt.getFileContent(pepperEnv, minion, "/tmp/${archiveName}", true, null, false)

                sh "mkdir -p ${localResultsDir}"
                writeFile file: "${archiveName}", text: fileContents
                sh "tar --strip-components 1 -xf ${archiveName} --directory ${localResultsDir}; rm -f ${archiveName}"

                // Remove archive which is not needed anymore
                salt.runSaltProcessStep(pepperEnv, minion, 'file.remove', "/tmp/${archiveName}")

                // Attempt to upload the scanning results to the dashboard
                if (UPLOAD_TO_DASHBOARD.toBoolean()) {
                    if (common.validInputParam('DASHBOARD_API_URL')) {
                        def cloudName = salt.getGrain(pepperEnv, minion, 'domain')['return'][0].values()[0].values()[0]
                        def nodeResults = readFile "${localResultsDir}/${benchmarkPathWithoutExtension}/results.json"
                        reportId = uploadResultToDashboard(DASHBOARD_API_URL, cloudName, minion, "openscap", reportId, nodeResults)
                    } else {
                        throw new Exception('Uploading to the dashboard is enabled but the DASHBOARD_API_URL was not set')
                    }
                }
            }
        }

        // Prepare archive
        sh "tar -cJf ${artifactsDir}.tar.xz ${artifactsDir}"

        // Archive the build output artifacts
        archiveArtifacts artifacts: "*.xz"
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
