# Throwaway single-node swarm for compoje's engine integration tests
# (test/integration/swarm_engines_test.clj): Docker-in-Docker with TLS on 2376
# (dind generates the certificates in /certs) plus sshd for the ssh transports.
# sshd settings go in sshd_config.d: it is included first, and the first value wins
# (Alpine's sshd_config sets AllowTcpForwarding no).
FROM docker:29-dind

RUN apk add --no-cache openssh-server \
 && ssh-keygen -A \
 && echo 'root:*' | chpasswd -e \
 && mkdir -p /root/.ssh && chmod 700 /root/.ssh \
 && mkdir -p /etc/ssh/sshd_config.d \
 && { echo 'PermitRootLogin prohibit-password'; \
      echo 'PasswordAuthentication no'; \
      echo 'AllowTcpForwarding yes'; \
      echo 'AllowStreamLocalForwarding yes'; } > /etc/ssh/sshd_config.d/compoje.conf \
 && printf '#!/bin/sh\n/usr/sbin/sshd\nexec dockerd-entrypoint.sh "$@"\n' > /usr/local/bin/entrypoint-ssh.sh \
 && chmod +x /usr/local/bin/entrypoint-ssh.sh

ENV DOCKER_TLS_CERTDIR=/certs
EXPOSE 22 2376
ENTRYPOINT ["/usr/local/bin/entrypoint-ssh.sh"]
CMD []
