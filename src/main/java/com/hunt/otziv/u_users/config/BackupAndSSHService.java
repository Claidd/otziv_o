//package com.hunt.otziv.u_users.config;
//
//import com.jcraft.jsch.*;
//import org.springframework.stereotype.Service;
//
//import java.io.IOException;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//@Service
//public class BackupAndSSHService {
//
//    private static final String SSH_USERNAME = "root";
//    private static final String SSH_HOST = "ssh root@95.213.248.152";
//    private static final int SSH_PORT = 22;
//    private static final String SSH_PASSWORD = "iVVEPsjuPB4c";
//
//    public void performBackupAndSendEmail() {
//        System.out.println("Зашли в отправку бэка");
//        String backupFileName = new SimpleDateFormat("ddMMyyyyHHmmss").format(new Date()) + ".sql";
//        String backupFilePath = "/tmp/" + backupFileName;
//
//        try {
//            JSch jsch = new JSch();
//            Session session = jsch.getSession(SSH_USERNAME, SSH_HOST, SSH_PORT);
//            session.setPassword(SSH_PASSWORD);
//            session.setConfig("StrictHostKeyChecking", "no");
//            session.connect();
//
//            String command = String.format(
//                    "mysqldump -u%s -p%s otziv > %s",
//                    "root",
//                    "Rkfql54532111", // Поменяйте на реальный пароль
//                    backupFilePath
//            );
//
//            ChannelExec channel = (ChannelExec) session.openChannel("exec");
//            channel.setCommand(command);
//            channel.connect();
//
//            // Ждем завершения выполнения команды
//            while (!channel.isClosed()) {
//                Thread.sleep(1000);
//            }
//
//            // Получаем код завершения команды
//            int exitStatus = channel.getExitStatus();
//            channel.disconnect();
//            session.disconnect();
//
//            if (exitStatus == 0) {
//                System.out.println("Database backup successful: " + backupFilePath);
//                // Отправка почты с вложением и удаление файла бэкапа
//            } else {
//                System.err.println("Database backup failed with exit status: " + exitStatus);
//            }
//        } catch (JSchException | InterruptedException e) {
//            e.printStackTrace();
//        }
//    }
//}

