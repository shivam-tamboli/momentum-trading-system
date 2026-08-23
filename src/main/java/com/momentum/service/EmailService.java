package com.momentum.service;

import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender javaMailSender;
    private final UserRepository userRepository;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public EmailService(JavaMailSender javaMailSender, UserRepository userRepository) {
        this.javaMailSender = javaMailSender;
        this.userRepository = userRepository;
    }

    public void notifyAllUsers(String subject, String body) {
        List<User> users = userRepository.findAll();

        for (User user : users) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromAddress);
                message.setTo(user.getEmail());
                message.setSubject(subject);
                message.setText(body);

                javaMailSender.send(message);

                log.info("Sent recommendation email to {}", user.getEmail());
            } catch (Exception e) {
                log.warn("Failed to send email to {}: {}", user.getEmail(), e.getMessage());
            }
        }
    }
}
