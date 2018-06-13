/**
 *
 * Launch pytest frameworks in Jenkins
 *
 * Expected parameters:
 *   SALT_MASTER_URL                 URL of Salt master
 *   SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 *
 *   TESTS_SET                       Leave empty for full run or choose a file (test)
 *   TESTS_REPO                      Repo to clone
 *   TESTS_SETTINGS                  Additional environment varibales to apply
 *   PROXY                           Proxy to use for cloning repo or for pip
 *
 */

validate = new com.mirantis.mcp.Validate()

node() {
    try{
        stage('Initialization') {
            validate.prepareVenv(TESTS_REPO, PROXY)
        }

        stage('Run Tests') {
            validate.runTests(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, TESTS_SET, '', TESTS_SETTINGS)
        }
        stage ('Publish results') {
            archiveArtifacts artifacts: "*"
            junit "*.xml"
            plot csvFileName: 'plot-8634d2fe-dc48-4713-99f9-b69a381483aa.csv',
                 group: 'SPT',
                 style: 'line',
                 title: 'SPT Glance results',
                 xmlSeries: [[
                 file: "report.xml",
                 nodeType: 'NODESET',
                 url: '',
                 xpath: '/testsuite/testcase[@name="test_speed_glance"]/properties/property']]
            plot csvFileName: 'plot-8634d2fe-dc48-4713-99f9-b69a381483bb.csv',
                 group: 'SPT',
                 style: 'line',
                 title: 'SPT HW2HW results',
                 xmlSeries: [[
                 file: "report.xml",
                 nodeType: 'NODESET',
                 url: '',
                 xpath: '/testsuite/testcase[@classname="cvp-spt.cvp_spt.tests.test_hw2hw"]/properties/property']]
            plot csvFileName: 'plot-8634d2fe-dc48-4713-99f9-b69a381483bc.csv',
                 group: 'SPT',
                 style: 'line',
                 title: 'SPT VM2VM results',
                 xmlSeries: [[
                 file: "report.xml",
                 nodeType: 'NODESET',
                 url: '',
                 xpath: '/testsuite/testcase[@classname="cvp-spt.cvp_spt.tests.test_vm2vm"]/properties/property']]
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    }
}
