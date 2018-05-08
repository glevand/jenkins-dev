#!groovy
/* Runs the CoreOS Container Linux Kola test suite using the mantle-runner
 * docker container. */

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '5')),
    parameters([
        choice(name: 'ARCH',
            choices: "arm64\namd64",
            defaultValue: 'arm64',
            description: 'System architecture.'),
        choice(name: 'COREOS_CHANNEL',
            choices: "alpha\nbeta\nstable",
            defaultValue: 'alpha',
            description: 'CoreOS Container Linux channel to test'),
       string(name: 'COREOS_VERSION',
            defaultValue: 'current',
            description: 'CoreOS Container Linux version to test'),
        booleanParam(name: 'KOLA_DEBUG',
            defaultValue: false,
            description: 'Run Kola in debug mode'),
        choice(name: 'KOLA_PARALLEL',
            choices: "2\n4\n8\n16\n32\n64\n128\n256",
            defaultValue: '16',
            description: 'Number of Kola tests to run in parallel'),
        string(name: 'KOLA_TESTS',
            defaultValue: 'coreos.basic',
            description: 'Kola tests to run (glob pattern)'),
        booleanParam(name: 'CURL_DOWNLOAD',
            defaultValue: false,
            description: 'Download image from CURL_URL'),
        string(name: 'CURL_URL',
            defaultValue: 'file:///tmp',
            description: 'URL for curl downloads'),
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0
String arch = params.ARCH

node("${arch} && kvm && sudo") {
    stage('Build') {
        echo "[${arch}] Copying artifacts..."
        step([$class: 'CopyArtifact',
                fingerprintArtifacts: true,
                projectName: '/mantle/master-builder',
                selector: [$class: 'StatusBuildSelector', stable: false],
                filter: "mantle-runner-${arch}.tar, run-mantle-${arch}",
        ])
        rc = sh returnStatus: true, script: '''#!/bin/bash -ex

arch="${ARCH}"
job_name="${JOB_NAME##*/}"
tapfile="${job_name}-${arch}.tap"
downloads="downloads"

curl_download () {
    pushd ${downloads}
    curl -sS -O ${CURL_URL}/coreos_production_qemu_uefi_efi_code.fd

    local a
    local alts="
        coreos_production_image.bin
        coreos_production_image.bin.bz2
    "
    for a in ${alts} end; do
        if [[ "${a}" == "end" ]]; then
            echo "curl_download: Failed all alts"
            exit 1
        fi
        if ( curl -sS -f -O ${CURL_URL}/${a} ); then
            break
        fi
    done
    popd
}

cork_download () {
    ./run-mantle-${arch} --verbose cork download-image \
        --cache-dir=${downloads} \
        --platform=qemu_uefi \
        --root="https://${COREOS_CHANNEL}.release.core-os.net/${arch}-usr/${COREOS_VERSION}" \
        --verify=false
}

download_image () {
    if [[ "${CURL_DOWNLOAD}" == "true" ]]; then
        curl_download
    else
        cork_download
    fi
}

unzip_image () {
    local bz2="${chroot}/${downloads}/coreos_production_image.bin.bz2"

    if [[ -f "${bz2}" ]]; then
        bunzip2 --force --keep "${bz2}"
    fi
}

sudo rm -rf *.tap src/scripts/_kola_temp tmp _kola_temp* ${downloads}
mkdir -p ${downloads}

docker load --input mantle-runner-${arch}.tar
docker images

download_image
unzip_image

if [[ "${KOLA_DEBUG}" == "true" ]]; then
   kola_extra="--debug"
fi

./run-mantle-${arch} --verbose --kvm -- \
    timeout --signal=SIGQUIT 120m \
    kola run \
    ${KOLA_TESTS} \
    ${kola_extra} \
    --board="${arch}-usr" \
    --parallel=${KOLA_PARALLEL} \
    --platform=qemu \
    --qemu-bios=${downloads}/coreos_production_qemu_uefi_efi_code.fd \
    --qemu-image=${downloads}/coreos_production_image.bin \
    --tapfile=${tapfile}

'''  /* Editor quote safety: ' */

    }

    stage('Post-build') {
        echo "Post-build: TODO"
        step([$class: 'TapPublisher',
              discardOldReports: false,
              enableSubtests: true,
              failIfNoResults: true,
              failedTestsMarkBuildAsFailure: true,
              flattenTapResult: false,
              includeCommentDiagnostics: true,
              outputTapToConsole: true,
              planRequired: true,
              showOnlyFailures: false,
              skipIfBuildNotOk: false,
              stripSingleParents: false,
              testResults: '*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'
