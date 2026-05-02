//package com.hunt.otziv.u_users.config;
//
//import com.github.dockerjava.api.DockerClient;
//import com.github.dockerjava.api.command.ExecCreateCmdResponse;
//import com.github.dockerjava.api.model.Container;
//import com.github.dockerjava.core.DockerClientBuilder;
//import com.github.dockerjava.core.command.ExecStartResultCallback;
//import org.springframework.stereotype.Service;
//
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.io.OutputStream;
//import java.util.concurrent.TimeUnit;
//
//@Service
//public class DockerService {
//        // Метод createMysqlBackup принимает два аргумента: containerName (имя Docker-контейнера,
//        // где размещена база данных MySQL) и backupPath (место, куда будет сохранен файл резервной копии).
//    public void createMysqlBackup(String containerName, String backupPath) throws IOException {
//
//        // Здесь DockerClient - это интерфейс, предоставляемый Docker API для Java, а DockerClientBuilder используется
//        // для создания его экземпляра
//        DockerClient dockerClient = DockerClientBuilder.getInstance("tcp://host.docker.internal:2375").build();
//        System.out.println(dockerClient);
//
//        // Найти контейнер MySQL
//        // Этот фрагмент кода использует Docker-клиент для перечисления всех Docker-контейнеров, фильтрует для поиска
//        // того, который совпадает с указанным именем контейнера, и выбрасывает исключение, если не может найти
//        // контейнер с этим именем.
//        Container mysqlContainer = dockerClient.listContainersCmd().exec().stream()
//                .filter(container -> container.getNames()[0].equals(containerName))
//                .findFirst()
//                .orElseThrow(() -> new RuntimeException("No such container"));
//
//
//        // Оставшаяся часть функции выполняет команду дампа базы данных MySQL в Docker-контейнере, создавая тем самым
//        // резервную копию базы данных. Это делается с помощью команды CLI mysqldump в оболочке bash внутри Docker-
//        // контейнера. После создания резервной копии она копируется в приложение с помощью метода
//        // copyArchiveFromContainerCmd.
//        System.out.println(mysqlContainer);
//        // Создание резервной копии
//        String mysqldumpCommand = "mysqldump -u root -pRkfql54532111 otziv > /backup/backup-otziv.sql";
//
//        System.out.println(mysqlContainer);
//
//        ExecCreateCmdResponse cmdResponse = dockerClient.execCreateCmd(mysqlContainer.getId())
//                .withCmd("/bin/bash", "-c", mysqldumpCommand)
//                .withAttachStdout(true)
//                .withAttachStderr(true)
//                .exec();
//        System.out.println(cmdResponse);
//        try {
//            dockerClient.execStartCmd(cmdResponse.getId())
//                    .exec(new ExecStartResultCallback(System.out, System.err))
//                    .awaitCompletion(30, TimeUnit.SECONDS);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        System.out.println("Создание");
//
//        // Копирование файла резервной копии обратно в приложение
//        try (OutputStream outputStream = new FileOutputStream(backupPath)) {
//            dockerClient.copyArchiveFromContainerCmd(mysqlContainer.getId(), "/tmp/backup.sql")
//                    .exec()
//                    .transferTo(outputStream);
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//
//        dockerClient.close();
//    }
//}