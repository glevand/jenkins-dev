#!groovy
/* Runs the CoreOS Container Linux Kola test suite using a chroot. */

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
        booleanParam(name: 'NEW_CHROOT',
            defaultValue: false,
            description: 'Force creation of a new chroot'),
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
                filter: "bin/${arch}/cork, bin/${arch}/kola, bin/*/kolet",
        ])
        rc = sh returnStatus: true, script: '''#!/bin/bash -ex

arch="${ARCH}"
job_name="${JOB_NAME##*/}"
tapfile="${job_name}-${arch}.tap"
downloads="downloads"

chroot="chroot"
if [[ "${arch}" == "arm64" ]]; then
    docker_image="arm64v8/ubuntu:17.10"
else
    docker_image="ubuntu:17.10"
fi

cleanup () {
    sudo umount -l ${chroot}/{proc,dev,sys,run}
    cp -vf ${chroot}/${tapfile} .
}

create_chroot () {
    if [[ "${NEW_CHROOT}" == "false" ]] \
        && [[ -f "${chroot}/etc/os-release" && -f "${chroot}/update" ]] \
        && egrep 'docker=' ${chroot}/update | egrep ${docker_image} > /dev/null; then
        touch ${chroot}/existing
        echo "Using existing chroot"
        return 0
    fi

    echo "Creating chroot"
    sudo rm -rf ${chroot}
    riid=$(sudo --preserve-env rkt --insecure-options=image fetch "docker://${docker_image}")
    sudo --preserve-env rkt image extract --overwrite --rootfs-only "${riid}" ${chroot}
    sudo --preserve-env rkt image rm "${riid}"

    sudo chown ${USER}: ${chroot}
    mkdir -p ${chroot}/${downloads}

    cat << EOF > ${chroot}/update
#!bin/bash
# docker=${docker_image}
apt-get update
apt-get -y install qemu-system-arm qemu-system-x86 dnsmasq
rm -rf /var/lib/apt/lists/*
EOF
    chmod +x ${chroot}/update
}

update_chroot () {
    create_chroot
    sudo cp -f /etc/resolv.conf ${chroot}/etc/resolv.conf
    sudo chroot ${chroot} bash -x /update
}

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

sudo rm -rf *.tap src/scripts/_kola_temp tmp _kola_temp* ${chroot}/${downloads}
mkdir -p ${downloads}

update_chroot

download_image
unzip_image

# copy latest mantle binaries
sudo mkdir -p ${chroot}/usr/lib/kola/{amd64,arm64}
sudo cp -vf -t ${chroot}/usr/lib/kola/arm64 bin/arm64/*
sudo cp -vf -t ${chroot}/usr/lib/kola/amd64 bin/amd64/*

trap cleanup EXIT PIPE RETURN

sudo mount -t proc /proc ${chroot}/proc
sudo mount --rbind /dev ${chroot}/dev
sudo mount --make-rslave ${chroot}/dev
sudo mount --rbind /sys ${chroot}/sys
sudo mount --make-rslave ${chroot}/sys
sudo mount --rbind /run ${chroot}/run
sudo mount --make-rslave ${chroot}/run

if [[ "${KOLA_DEBUG}" == "true" ]]; then
   kola_extra="--debug"
fi

sudo chroot ${chroot} /usr/lib/kola/${arch}/kola run \
    ${KOLA_TESTS} \
    ${kola_extra} \
    --board="${arch}-usr" \
    --parallel=${KOLA_PARALLEL} \
    --platform=qemu \
    --qemu-bios=/${downloads}/coreos_production_qemu_uefi_efi_code.fd \
    --qemu-image=/${downloads}/coreos_production_image.bin \
    --tapfile=/${tapfile}

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
