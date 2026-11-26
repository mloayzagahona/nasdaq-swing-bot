package com.openclaw.bot.email;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Arrays;
import java.util.Properties;

/** Real SMTP delivery via Jakarta Mail. Configure with SMTP_HOST/PORT/USERNAME/PASSWORD/MAIL_FROM/MAIL_TO. */
public final class SmtpEmailSender implements EmailSender {

    private final Session session;
    private final String from;
    private final String[] to;

    public SmtpEmailSender(String host, int port, String user, String password,
                           String from, String to) {
        this.from = from;
        this.to = Arrays.stream(to.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", user != null && !user.isEmpty() ? "true" : "false");
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "20000");

        final String u = user == null ? "" : user;
        final String p = password == null ? "" : password;
        this.session = Session.getInstance(props, u.isEmpty() ? null : new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(u, p);
            }
        });
    }

    @Override
    public void send(String subject, String body) throws MessagingException {
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        for (String t : to) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(t));
        msg.setSubject(subject);
        msg.setText(body);
        Transport.send(msg);
    }
}
