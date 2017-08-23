#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '5')),

    parameters([
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '1bb768fc-940d-4a95-95d0-27c1153e7fa0',
                    description: 'AWS credentials list for AMI creation and releasing',
                    name: 'AWS_RELEASE_CREDS',
                    required: true),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
                    defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
                    description: 'Credentials with permissions required by "kola run --platform=aws"',
                    name: 'AWS_TEST_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '7ab88376-e794-4128-b644-41c83c89e76d',
                    description: 'JSON credentials file for all Azure clouds used by plume',
                    name: 'AZURE_CREDS',
                    required: true),
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
                    defaultValue: '',
                    description: 'Credential ID for SSH Git clone URLs',
                    name: 'BUILDS_CLONE_CREDS',
                    required: false),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        text(name: 'FORMAT_LIST',
             defaultValue: 'pxe qemu_uefi',
             description: 'Space-separated list of VM image formats to build'),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for downloading development files from \
the Google Storage URL, requires read permission''',
                    name: 'GS_DEVEL_CREDS',
                    required: true),
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for uploading release files to the \
Google Storage URL, requires write permission''',
                    name: 'GS_RELEASE_CREDS',
                    required: true),
        string(name: 'GS_RELEASE_DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are downloaded'),
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are uploaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl',
                    defaultValue: 'd67b5bde-d138-487a-9da3-0f5f5f157310',
                    description: 'Credentials to run hosts in PACKET_PROJECT',
                    name: 'PACKET_CREDS',
                    required: true),
        string(name: 'PACKET_PROJECT',
               defaultValue: '9da29e12-d97c-4d6e-b5aa-72174390d57a',
               description: 'The Packet project ID to run test machines'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'buildbot-official.EF4B4ED9.subkey.gpg',
                    description: 'Credential ID for a GPG private key file',
                    name: 'SIGNING_CREDS',
                    required: true),
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs'),
        choice(name: 'HAVE_ARM64_NODE',
               choices: "1\n0"),

    ])
])

/* Define downstream testing/prerelease builds for specific formats.  */
def downstreams = [
    'ami': {
        if (params.BOARD == 'amd64-usr')
            echo "1 ami: ${params.BOARD}: ${it.format} -- ${it.version}"
    },
    'azure': {
        if (params.BOARD == 'amd64-usr' && params.COREOS_OFFICIAL == '1')
            echo "2 azure: ${params.BOARD}: ${it.format} -- ${it.version}"
    },
    'gce': {
        if (params.BOARD == 'amd64-usr')
            echo "3 ${params.BOARD}: ${it.format} -- ${it.version}"
    },
    'packet': {
        if (params.BOARD == 'amd64-usr')
            echo "4 ${params.BOARD}: ${it.format} -- ${it.version}"
        if (params.BOARD == 'arm64-usr')
            echo "6 ${params.BOARD}: ${it.format} -- ${it.version} -> arm64"
    },
    'qemu_uefi': {
        echo "5 ${params.BOARD}: ${it.format} -- ${it.version}"
        if (params.BOARD == 'arm64-usr' && params.HAVE_ARM64_NODE == '1') {
            echo "6 ${params.BOARD}: ${it.format} -- ${it.version} -> arm64"
            //for (node in jenkins.model.Jenkins.instance.getNodes()) {
            //    echo "7 check: ${node.name}"
            //    if (node.toComputer().getLabel("arm64") && node.toComputer().isOnline()) {
            //        echo "8 found: ${node.name}"
            //        break
            //    }
            //}
            echo "9 done"
        }
    }
]

/* Force this as an ArrayList for serializability, or Jenkins explodes.  */
ArrayList<String> format_list = params.FORMAT_LIST.split()

for (format in format_list) {
    def FORMAT = format  /* This MUST use fresh variables per iteration.  */
    def version = '112233-test'

        if (FORMAT in downstreams)
            downstreams[FORMAT](version: version, format: FORMAT)
}
