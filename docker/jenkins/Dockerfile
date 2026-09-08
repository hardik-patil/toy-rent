# Jenkins controller image for ToyRental — stock jenkins/jenkins:lts plus a
# Python 3 toolchain for the tests/ pytest suite (tests/Jenkinsfile).
#
# The stock image has no python3, and the `jenkins` user has no sudo, so the
# pipeline can't install it itself. Bake it in here instead.
#
# Build + swap the running container in (the jenkins_home named volume, and
# therefore every job/plugin/build, is preserved across the rm/run):
#
#   docker build -t toyrental/jenkins:lts docker/jenkins
#   docker rm -f jenkins
#   docker run -d --name jenkins \
#     -p 8080:8080 -p 50000:50000 \
#     -v jenkins_home:/var/jenkins_home \
#     -v "C:\Users\USER\Software\apache-jmeter-5.6.3:/opt/jmeter:ro" \
#     toyrental/jenkins:lts
#
# (Same `docker run` as STARTUP.md's Jenkins section, only the image name changes.)

FROM jenkins/jenkins:lts

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        python3 python3-venv python3-pip curl \
 && rm -rf /var/lib/apt/lists/*
USER jenkins
