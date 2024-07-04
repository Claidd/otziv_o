//package com.hunt.otziv.u_users.config;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.mail.javamail.JavaMailSender;
//import org.springframework.mail.javamail.MimeMessageHelper;
//import jakarta.mail.MessagingException;
//import jakarta.mail.internet.MimeMessage;
//import org.springframework.stereotype.Component;
//import org.springframework.stereotype.Service;
//
//import java.io.*;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//
//@Component
//@RequiredArgsConstructor
//public class BackupAndEmailService {
//
//
//
//    public void performBackupAndSendEmail() {
//        System.out.println("Зашли в отправку бэка");
//        String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/share/" + backupFileName;
//        String containerId = getMySQLContainerId("mymysql"); // Получаем ID контейнера MySQL
//        createDatabaseBackup("mymysql", backupFilePath);
//        createDatabaseBackup("2017f92d86fef706f22498b4044e5b93e9cda0bf4c33e99275a4b450c8ade4ba", backupFilePath);
//
//        if (containerId != null) {
//            createDatabaseBackup(containerId, backupFilePath);
//            System.out.println("Создали бэка");
//            // sendEmailWithAttachment(backupFilePath); // Отправляем email с вложением
//            System.out.println("отправили");
//            // deleteBackupFile(backupFilePath); // Удаляем файл бэкапа
//        } else {
//            System.err.println("MySQL контейнер не найден.");
//        }
//    }
//
//    private String getMySQLContainerId(String containerName) {
//        System.out.println("ввели: " + containerName);
//        try {
//            ProcessBuilder processBuilder = new ProcessBuilder(
//                    "docker", "ps", "-q", "--filter", "name=" + containerName);
//            Process process = processBuilder.start();
//            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
//            String containerId = reader.readLine();
//            System.out.println(containerId);
//            process.waitFor();
//            return containerId;
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//            return null;
//        }
//    }
//
//    private void createDatabaseBackup(String containerId, String backupFilePath) {
//        String dbName = "otziv";
//        String dbUser = "hunt";
//        String dbPassword = "Rkfql54532111";
//        String command = String.format(
//                "docker exec -i %s sh -c 'mysqldump -u%s -p%s %s > %s'",
//                containerId,
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            System.out.println("Executing command: " + command);
//            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }



//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            MimeMessageHelper helper = new MimeMessageHelper(message, true);
//            helper.setTo("iquest138@gmail.com");
//            helper.setSubject("Daily Database Backup");
//            helper.setText("Please find the attached database backup.");
//            File backupFile = new File(backupFilePath);
//            helper.addAttachment(backupFile.getName(), backupFile);
//            mailSender.send(message);
//        } catch (MessagingException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            System.out.println("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            System.err.println("Failed to delete the backup file: " + e.getMessage());
//        }
//    }
//}
















//@Service
//@RequiredArgsConstructor
//public class BackupAndEmailService {
//
//    private final JavaMailSender mailSender;
//
//    // Ежедневный запуск в полночь
//    // @Scheduled(cron = "0 0 0 * * ?")
//    public void performBackupAndSendEmail() {
//        System.out.println("Зашли в отправку бэка");
//        String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/share/" + backupFileName;
//        createDatabaseBackup("mymysql", backupFilePath);
//        String containerId = getMySQLContainerId();
//        System.out.println(containerId);
//        if (containerId != null) {
//            createDatabaseBackup("mymysql", backupFilePath);
//            System.out.println("Создали бэка");
////            sendEmailWithAttachment(backupFilePath);
//            System.out.println("отправили");
////            deleteBackupFile(backupFilePath);
//        } else {
//            System.err.println("MySQL контейнер не найден.");
//        }
//    }
//
//    private String getMySQLContainerId() {
//        String containerName = "mymysql"; // замените на нужное имя контейнера
//        System.out.println("ввели: " + containerName);
//        try {
////            ProcessBuilder processBuilder = new ProcessBuilder(
////                    "docker", "ps", "-q", "--filter", "name=" + containerName);
//            ProcessBuilder processBuilder = new ProcessBuilder(
//                    "docker ps -q --filter name=mymysql");
//            Process process = processBuilder.start();
//            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
//            String containerId = reader.readLine();
//            System.out.println(containerId);
//            process.waitFor();
//            return containerId;
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//            return null;
//        }
//    }
//
//    private void createDatabaseBackup(String containerId, String backupFilePath) {
//        String dbName = "otziv";
//        String dbUser = "hunt";
//        String dbPassword = "Rkfql54532111";
//        String command = String.format(
//                "docker exec %s sh -c 'mysqldump -u%s -p%s %s > /share/backup-otziv.sql'",
//                containerId,
//                dbUser,
//                dbPassword,
//                dbName
//        );
//
//        try {
//            System.out.println(command);
//            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            MimeMessageHelper helper = new MimeMessageHelper(message, true);
//            helper.setTo("iquest138@gmail.com");
//            helper.setSubject("Daily Database Backup");
//            helper.setText("Please find the attached database backup.");
//            File backupFile = new File(backupFilePath);
//            helper.addAttachment(backupFile.getName(), backupFile);
//            mailSender.send(message);
//        } catch (MessagingException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            System.out.println("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            System.err.println("Failed to delete the backup file: " + e.getMessage());
//        }
//    }
//}




















//import lombok.RequiredArgsConstructor;
//import org.springframework.mail.javamail.JavaMailSender;
//import org.springframework.mail.javamail.MimeMessageHelper;
//import jakarta.mail.MessagingException;
//import jakarta.mail.internet.MimeMessage;
//import org.springframework.stereotype.Component;
//import org.springframework.stereotype.Service;
//import java.io.*;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//
//@Component
//@RequiredArgsConstructor
//public class BackupAndEmailService {
//
//    private final JavaMailSender mailSender;
//
//    // Ежедневный запуск в полночь
////    @Scheduled(cron = "0 0 0 * * ?")
//    public void performBackupAndSendEmail() {
//        System.out.println("Зашли в отправку бэка");
//        String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/tmp/" + backupFileName;
//        String containerId = getMySQLContainerId();
//        System.out.println(containerId);
//        if (containerId != null) {
//            createDatabaseBackup(containerId, backupFilePath);
//            System.out.println("Создали бэка");
//            sendEmailWithAttachment(backupFilePath);
//            System.out.println("отправили");
//            deleteBackupFile(backupFilePath);
//        } else {
//            System.err.println("MySQL контейнер не найден.");
//        }
//    }
//
//    private String getMySQLContainerId() {
//        String containerName = "mysql"; // замените на нужное имя контейнера
//        try {
//            ProcessBuilder processBuilder = new ProcessBuilder(
//                    "docker ps -q --filter name=" + containerName);
//            Process process = processBuilder.start();
//            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
//            String containerId = reader.readLine();
//            System.out.println(containerId);
//            process.waitFor();
//            return containerId;
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//            return null;
//        }
//    }
//
//
//    private void createDatabaseBackup(String containerId, String backupFilePath) {
//        String dbName = "otziv";
//        String dbUser = "hunt";
//        String dbPassword = "Rkfql54532111";
//        String command = String.format(
//                "docker exec -i %s /usr/bin/mysqldump -u %s -p %s %s > %s",
//                containerId,
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            System.out.println(command);
//            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            MimeMessageHelper helper = new MimeMessageHelper(message, true);
//            helper.setTo("iquest138@gmail.com");
//            helper.setSubject("Daily Database Backup");
//            helper.setText("Please find the attached database backup.");
//            File backupFile = new File(backupFilePath);
//            helper.addAttachment(backupFile.getName(), backupFile);
//            mailSender.send(message);
//        } catch (MessagingException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            System.out.println("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            System.err.println("Failed to delete the backup file: " + e.getMessage());
//        }
//    }
//}

















    // Ежедневный запуск в полночь
//    @Scheduled(cron = "0 0 0 * * ?")
//    public void performBackupAndSendEmail() {
//        System.out.println("Зашли в отправку бэка");
//        String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/tmp/" + backupFileName;
//        createDatabaseBackup(backupFilePath);
//        System.out.println("Создали бэка");
//        sendEmailWithAttachment(backupFilePath);
//        System.out.println("отправили");
//        deleteBackupFile(backupFilePath);
//    }
//
//    private void createDatabaseBackup(String backupFilePath) {
//        String dbName = "otziv";
//        String dbUser = "hunt";
//        String dbPassword = "Rkfql54532111";
//        String command = String.format(
//                "docker exec -i %s /usr/bin/mysqldump -u%s -p%s %s > %s",
//                "bb7ece6dd86a", // Замените на имя вашего контейнера
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            MimeMessageHelper helper = new MimeMessageHelper(message, true);
//            helper.setTo("iquest138@gmail.com");
//            helper.setSubject("Daily Database Backup");
//            helper.setText("Please find the attached database backup.");
//            File backupFile = new File(backupFilePath);
//            helper.addAttachment(backupFile.getName(), backupFile);
//            mailSender.send(message);
//        } catch (MessagingException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            System.out.println("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            System.err.println("Failed to delete the backup file: " + e.getMessage());
//        }
//    }



















//    private void createDatabaseBackup(String dbName, String dbUser, String dbPassword, String backupFilePath) {
//        String command = String.format(
//                "mysqldump -u%s -p%s %s > %s",
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }

//     Ежедневный запуск в полночь
//    @Scheduled(cron = "0 0 0 * * ?")
//    public void backupDatabase(){
//        System.out.println("Зашли в отправку");
//        String dbName = "otziv";
//        String dbUser  = "hunt";
//        String dbPassword = "545321";
//        String backupFileName = new SimpleDateFormat("ddMMyyyyddHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/tmp/" + backupFileName;
//        String command = String.format(
//                "docker exec -i %s /usr/bin/mysqldump -u%s -p%s %s > %s",
//                "your_db_container_name",
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            System.out.println("Начали делать бэкап");
//            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//                sendEmailWithAttachment(backupFilePath, backupFileName);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (InterruptedException | MessagingException | IOException e) {
//            e.printStackTrace();
//        }
//    }




//    private void sendEmailWithAttachment(String filePath, String fileName) throws MessagingException {
//        MimeMessage message = mailSender.createMimeMessage();
//        MimeMessageHelper helper = new MimeMessageHelper(message, true);
//
//        helper.setTo("iquest138@gmail.com");
//        helper.setSubject("Database Backup");
//        helper.setText("Please find attached the latest database backup.");
//
//        File file = new File(filePath);
//        helper.addAttachment(fileName, file);
//
//        mailSender.send(message);
//        System.out.println("Email sent with attachment: " + filePath);
//
//        // Удаление временного файла после отправки
//        file.delete();
//    }














//@Service
//@RequiredArgsConstructor
//public class DatabaseBackupScheduler {
//    private static final Logger logger = LoggerFactory.getLogger(DatabaseBackupScheduler.class.getName());
//
//    private final JavaMailSender mailSender;
//
//    @Value("${spring.datasource.name}")
//    private String dbName;
//
//    @Value("${spring.datasource.username}")
//    private String dbUser;
//
//    @Value("${spring.datasource.password}")
//    private String dbPassword;
//
//    public void performBackupAndSendEmail() {
//        String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/tmp/" + backupFileName;
//        createDatabaseBackup(dbName, dbUser, dbPassword, backupFilePath);
//        sendEmailWithAttachment(backupFilePath);
//        deleteBackupFile(backupFilePath);
//    }
//
//        private void createDatabaseBackup(String dbName, String dbUser, String dbPassword, String backupFilePath) {
//            String mysqlDumpPath = "/usr/bin/mysqldump"; // Укажите путь к mysqldump на вашей системе
//            String command = String.format(
//                    "mysqldump -u%s -p%s %s -r\"%s\"",
//                    mysqlDumpPath,
//                    dbUser,
//                    dbPassword,
//                    dbName
//            );
//
//            try {
//                ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
//                processBuilder.inheritIO();
//                Process process = processBuilder.start();
//
//                // Считываем ошибку mysqldump
//                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
//                    reader.lines().forEach(line -> logger.error("mysqldump error: " + line));
//                }
//
//                int exitCode = process.waitFor();
//
//                if (exitCode == 0) {
//                    logger.info("Database backup successful: " + backupFilePath);
//                } else {
//                    logger.error("Database backup failed with exit code: " + exitCode);
//                }
//            } catch (IOException | InterruptedException e) {
//                logger.error("Error during database backup: " + e.getMessage(), e);
//            }
//        }
//
//
//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            createHelperForEmail(message, backupFilePath);
//            mailSender.send(message);
//            logger.info("Email has been sent.");
//        } catch (MessagingException e) {
//            logger.error(e.getMessage(), e);
//        }
//    }
//
//    private void createHelperForEmail(MimeMessage message, String backupFilePath) throws MessagingException {
//        MimeMessageHelper helper = new MimeMessageHelper(message, true);
//        helper.setTo("iquest138@gmail.com");
//        helper.setSubject("Бэкап");
//        helper.setText("Бэкап 2");
//
//        File backupFile = new File(backupFilePath);
//        helper.addAttachment(backupFile.getName(), backupFile);
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            logger.info("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            logger.error("Failed to delete the backup file: " + e.getMessage());
//        }
//    }
//}

//@Service
//@AllArgsConstructor
//public class DatabaseBackupScheduler {
//
//    private JavaMailSender mailSender;
//
//    public void performBackupAndSendEmail() {
//        System.out.println("Зашли в отправку бэка");
//        String dbName = "otziv";
//        String dbUser = "hunt";
//        String dbPassword = "545321";
//        String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/tmp/" + backupFileName;
//
//        createDatabaseBackup(dbName, dbUser, dbPassword, backupFilePath);
//        System.out.println("Создали бэка");
//
//        sendEmailWithAttachment(backupFilePath);
//        System.out.println("Отправили");
//
//        deleteBackupFile(backupFilePath);
//    }
//
//    private void createDatabaseBackup(String dbName, String dbUser, String dbPassword, String backupFilePath) {
//        String command = String.format(
//                "mysqldump --opt -u%s -p%s %s > \"%s\"",
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            MimeMessageHelper helper = new MimeMessageHelper(message, true);
//            helper.setTo("iquest138@gmail.com");
//            helper.setSubject("Daily Database Backup");
//            helper.setText("Please find the attached database backup.");
//            File backupFile = new File(backupFilePath);
//            helper.addAttachment(backupFile.getName(), backupFile);
//            mailSender.send(message);
//            System.out.println("Отправили сообщение");
//        } catch (MessagingException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            System.out.println("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            System.err.println("Failed to delete the backup file: " + e.getMessage());
//        }
//    }
//}







//        @Scheduled(cron = "0 0 0 * * ?") // Every day at midnight
//public void performBackupAndSendEmail() {
//    System.out.println("Зашли в отправку бэка");
//    String dbName = "otziv";
//    String dbUser = "hunt";
//    String dbPassword = "545321";
//    String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//    String backupFilePath = "/tmp/" + backupFileName;
//    createDatabaseBackup(backupFilePath);
//    System.out.println("Создали бэка");
//    sendEmailWithAttachment(backupFilePath);
//    System.out.println("отправили");
//    deleteBackupFile(backupFilePath);
//}
//
//    private static void createDatabaseBackup(String dbName, String dbUser, String dbPassword, String backupFilePath) {
//        String command = String.format(
//                "mysqldump -u%s -p%s %s > %s",
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }
//
//
//
//
//
//
//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            MimeMessageHelper helper = new MimeMessageHelper(message, true);
//            helper.setTo("iquest138@gmail.com");
//            helper.setSubject("Daily Database Backup");
//            helper.setText("Please find the attached database backup.");
//            File backupFile = new File(backupFilePath);
//            helper.addAttachment(backupFile.getName(), backupFile);
//            mailSender.send(message);
//            System.out.println("Отправили сообщение");
//        } catch (MessagingException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            System.out.println("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            System.err.println("Failed to delete the backup file: " + e.getMessage());
//        }
//    }
//




//    private void createDatabaseBackup(String backupFilePath) {
//        String dbName = "otziv";
//        String dbUser = "hunt";
//        String dbPassword = "545321";
//        String command = String.format(
//                "docker exec -i %s /usr/bin/mysqldump -u%s -p%s %s > %s",
//                "1991f1d92c9c", // Замените на имя вашего контейнера
//                dbUser,
//                dbPassword,
//                dbName,
//                backupFilePath
//        );
//
//        try {
//            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
//            processBuilder.inheritIO();
//            Process process = processBuilder.start();
//            int exitCode = process.waitFor();
//
//            if (exitCode == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//            } else {
//                System.err.println("Database backup failed with exit code: " + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }


//    private static final Logger logger = LoggerFactory.getLogger(BackupAndEmailService.class);
//
//    private final JavaMailSender mailSender;
//
//    @Value("${spring.datasource.username}")
//    private String dbUser;
//
//    @Value("${spring.datasource.password}")
//    private String dbPassword;
//
//    @Value("${spring.datasource.url}")
//    private String dbUrl;
//
//    @Value("${ssh.username}")
//    private String sshUsername;
//
//    @Value("${ssh.password}")
//    private String sshPassword;
//
//    @Value("${ssh.host}")
//    private String sshHost;
//
//    @Value("${ssh.port}")
//    private int sshPort;
//
//    // Метод для выполнения создания бэкапа и отправки почты
//    public void performBackupAndSendEmail() {
//        logger.info("Зашли в отправку бэка");
//        String dbName = "otziv";
//        String backupFileName = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/tmp/" + backupFileName;
//
//        try {
//            boolean backupSuccess = createRemoteDatabaseBackup(dbName, backupFilePath);
//            if (backupSuccess) {
//                logger.info("Создали бэкап");
//
//                sendEmailWithAttachment(backupFilePath);
//                logger.info("Отправили");
//
//                deleteBackupFile(backupFilePath);
//            } else {
//                logger.error("Failed to create database backup.");
//            }
//        } catch (IOException e) {
//            logger.error("Error during backup and email process: " + e.getMessage(), e);
//        }
//    }
//
//    // Метод для создания резервной копии базы данных по SSH
//    private boolean createRemoteDatabaseBackup(String dbName, String backupFilePath) throws IOException {
//        JSch jsch = new JSch();
//        Session session = null;
//        ChannelExec channel = null;
//        FileOutputStream outputStream = null;
//
//        try {
//            session = jsch.getSession(sshUsername, sshHost, sshPort);
//            session.setPassword(sshPassword);
//            session.setConfig("StrictHostKeyChecking", "no");
//            session.connect();
//
//            String command = String.format(
//                    "mysqldump -u%s -p%s %s",
//                    dbUser,
//                    dbPassword,
//                    dbName
//            );
//
//            channel = (ChannelExec) session.openChannel("exec");
//            channel.setCommand(command);
//            channel.setInputStream(null);
//            channel.setErrStream(System.err);
//
//            outputStream = new FileOutputStream(new File(backupFilePath));
//
//            channel.setOutputStream(outputStream);
//            channel.connect();
//
//            while (!channel.isClosed()) {
//                Thread.sleep(1000);
//            }
//
//            return channel.getExitStatus() == 0;
//        } catch (Exception e) {
//            logger.error("Error during SSH database backup: " + e.getMessage(), e);
//            return false;
//        } finally {
//            if (outputStream != null) {
//                outputStream.close();
//            }
//            if (channel != null) {
//                channel.disconnect();
//            }
//            if (session != null) {
//                session.disconnect();
//            }
//        }
//    }
//    private void sendEmailWithAttachment(String backupFilePath) {
//        MimeMessage message = mailSender.createMimeMessage();
//        try {
//            createHelperForEmail(message, backupFilePath);
//            mailSender.send(message);
//            logger.info("Email has been sent.");
//        } catch (MessagingException e) {
//            logger.error(e.getMessage(), e);
//        }
//    }
//
//    private void createHelperForEmail(MimeMessage message, String backupFilePath) throws MessagingException {
//        MimeMessageHelper helper = new MimeMessageHelper(message, true);
//        helper.setTo("iquest138@gmail.com");
//        helper.setSubject("Бэкап");
//        helper.setText("Бэкап 2");
//
//        File backupFile = new File(backupFilePath);
//        helper.addAttachment(backupFile.getName(), backupFile);
//    }
//
//    private void deleteBackupFile(String backupFilePath) {
//        Path path = Paths.get(backupFilePath);
//        try {
//            Files.delete(path);
//            logger.info("Backup file deleted: " + backupFilePath);
//        } catch (IOException e) {
//            logger.error("Failed to delete the backup file: " + e.getMessage());
//        }
//    }
