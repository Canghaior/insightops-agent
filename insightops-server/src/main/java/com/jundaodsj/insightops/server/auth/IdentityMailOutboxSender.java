package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.IdentitySecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class IdentityMailOutboxSender {
    private static final Logger log = LoggerFactory.getLogger(IdentityMailOutboxSender.class);
    private final IdentityRepository repository;
    private final IdentitySecretCipher cipher;
    private final IdentityProperties properties;
    private final JavaMailSender sender;
    private final Clock clock;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + '-' + UUID.randomUUID();

    @Autowired
    public IdentityMailOutboxSender(IdentityRepository repository, IdentitySecretCipher cipher,
                                    IdentityProperties properties, JavaMailSender sender) {
        this(repository, cipher, properties, sender, Clock.systemUTC());
    }

    IdentityMailOutboxSender(IdentityRepository repository, IdentitySecretCipher cipher,
                             IdentityProperties properties, JavaMailSender sender, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.sender = sender;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${insightops.identity.mail.initial-delay-ms:20000}",
            fixedDelayString = "${insightops.identity.mail.poll-delay-ms:15000}")
    public void drain() {
        if (!properties.getMail().isEnabled()) return;
        Instant now = clock.instant();
        repository.claimMail(now, now.minusSeconds(Math.max(30, properties.getMail().getLeaseSeconds())),
                        Math.max(1, properties.getMail().getBatchSize()), workerId)
                .forEach(this::deliver);
    }

    private void deliver(IdentityRepository.MailTask task) {
        Instant now = clock.instant();
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getMail().getFrom());
            message.setTo(task.recipient());
            message.setSubject(task.subject());
            message.setText(cipher.decrypt(task.bodyCiphertext()));
            sender.send(message);
            repository.completeMail(task.id(), clock.instant());
            log.info("Identity mail delivered taskId={} template={}", task.id(), task.template());
        } catch (Exception exception) {
            boolean terminal = task.attempts() >= task.maxAttempts();
            long backoffMinutes = Math.min(60, 1L << Math.min(6, Math.max(0, task.attempts() - 1)));
            repository.failMail(task.id(), exception.getClass().getSimpleName(),
                    now.plus(Duration.ofMinutes(backoffMinutes)), terminal, now);
            log.warn("Identity mail failed taskId={} template={} terminal={} errorType={}",
                    task.id(), task.template(), terminal, exception.getClass().getSimpleName());
        }
    }
}
