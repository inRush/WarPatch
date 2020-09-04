package cn.inrush;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.MD5;
import com.beust.jcommander.JCommander;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Main {
    /**
     * 当前上下文路径
     */
    private static final String CURRENT_CONTEXT_PATH;

    static {
        String jarPath = ClassUtil.getLocationPath(Main.class);
        CURRENT_CONTEXT_PATH = FileUtil.getParent(jarPath, 1);
    }

    /**
     * 获取指定项目的最新记录
     *
     * @param project 项目名
     * @return last file record
     */
    private static File getLastRecord(String project) {
        String projectPath = CURRENT_CONTEXT_PATH + "/" + project;
        if (!FileUtil.isDirectory(projectPath)) {
            return null;
        }
        List<File> files = FileUtil.loopFiles(FileUtil.getAbsolutePath(projectPath));
        return files.stream().min((File o1, File o2) -> o2.getName().compareTo(o1.getName())).get();
    }

    /**
     * war 包内所有文件转换为文件路径和校验码组成的Map
     *
     * @param war war包
     * @return {@link Map}
     */
    private static Map<String, String> warToCheckSumMap(JarFile war) throws IOException {
        Enumeration<JarEntry> entries = war.entries();
        Map<String, String> warMap = new HashMap<>();
        while (entries.hasMoreElements()) {
            JarEntry element = entries.nextElement();
            if (element.isDirectory() || element.getName().contains("META-INF")) {
                continue;
            }
            InputStream is = war.getInputStream(element);
            String digestHex = MD5.create().digestHex(is);
            warMap.put(element.toString(), digestHex);
        }
        return warMap;
    }

    /**
     * 对比两个版本的记录,生产补丁包
     *
     * @param oldRecord             旧的记录
     * @param currentWarCheckSumMap 新的记录
     */
    private static List<PatchRecord> compare(File oldRecord, Map<String, String> currentWarCheckSumMap) {
        List<String> lines = FileUtil.readLines(oldRecord, StandardCharsets.UTF_8);
        Map<String, String> oldRecordCheckSumMap = new HashMap<>();
        for (String line : lines) {
            String[] temp = line.split(":");
            oldRecordCheckSumMap.put(temp[0], temp[1]);
        }
        // 对比不一样的文件和新增的文件
        List<PatchRecord> records = new ArrayList<>();
        for (Map.Entry<String, String> item :
                currentWarCheckSumMap.entrySet()) {
            if (!oldRecordCheckSumMap.containsKey(item.getKey())) {
                // 文件不存在,新增的文件
                records.add(new PatchRecord(item.getKey(), PatchRecord.Action.ADD));
                System.out.println(StrUtil.format("[Add] {}: {}", item.getKey(), item.getValue()));
                continue;
            }
            // 文件存在,对比校验码
            String oldCheckSum = oldRecordCheckSumMap.get(item.getKey());
            if (!StrUtil.equals(item.getValue(), oldCheckSum)) {
                // 校验码不一致,文件进行了修改操作
                records.add(new PatchRecord(item.getKey(), PatchRecord.Action.MODIFY));
                System.out.println(StrUtil.format("[Modify] {}: {} -> {}", item.getKey(), oldCheckSum, item.getValue()));
            }
        }
        for (Map.Entry<String, String> item :
                oldRecordCheckSumMap.entrySet()) {
            if (!currentWarCheckSumMap.containsKey(item.getKey())) {
                // 文件不存在, 被删除了
                records.add(new PatchRecord(item.getKey(), PatchRecord.Action.DELETE));
                System.out.println(StrUtil.format("[Delete] {}: {}", item.getKey(), item.getValue()));
            }
        }
        return records;
    }

    private static String init(String path) throws IOException {
        String projectName = FileUtil.getName(path);
        JarFile war = new JarFile(path);
        Map<String, String> map = warToCheckSumMap(war);
        String checkSumFilePath = FileUtil.getAbsolutePath(
                CURRENT_CONTEXT_PATH.concat("/").concat(projectName).concat("/").concat(DateUtil.format(new Date(), "YYYYMMDDHHmmss")));
        File file = FileUtil.touch(checkSumFilePath);
        FileUtil.writeMap(map, file, StandardCharsets.UTF_8, ":", true);
        war.close();
        return checkSumFilePath;
    }


    private static String createNewPatch(String warPath, String oldPatchPatch) throws IOException {
        JarFile war = new JarFile(warPath);
        String projectName = FileUtil.getName(warPath);
        Map<String, String> warMap = warToCheckSumMap(war);
        File lastRecord;
        if (StrUtil.isBlank(oldPatchPatch)) {
            // 没有传入旧的patch文件,那么就直接去最新的
            lastRecord = getLastRecord(projectName);
        } else {
            lastRecord = new File(oldPatchPatch);
        }
        Assert.notNull(lastRecord, "{} 该项目不存在patch记录,请使用[-init]参数初始化一个", projectName);
        List<PatchRecord> records = compare(lastRecord, warMap);
        Assert.isFalse(records.isEmpty(), "当前war包和{}的patch记录没有差异", lastRecord.getName());

        // create patch package
        File patchDir = FileUtil.mkdir(FileUtil.getAbsolutePath(CURRENT_CONTEXT_PATH + "/patch_" + DateUtil.format(new Date(), "YYYYMMDDHHmmss")));
        File patchFile = FileUtil.touch(FileUtil.getAbsolutePath(patchDir.getAbsolutePath() + "/patch"));
        File patchResource = FileUtil.mkdir(FileUtil.getAbsolutePath(patchDir.getAbsolutePath() + "/resource"));
        // create patch message file
        FileUtil.writeLines(records, patchFile, StandardCharsets.UTF_8);
        for (PatchRecord record :
                records) {
            if (record.getAction() == PatchRecord.Action.DELETE) {
                continue;
            }
            String patchResourceFilePath = patchResource.getAbsolutePath() + File.separator + record.getFile();
            InputStream is = war.getInputStream(war.getJarEntry(record.getFile()));
            FileUtil.writeFromStream(is, patchResourceFilePath);
        }

        war.close();
        return patchDir.getAbsolutePath();
    }

    private static void rollBack(List<PatchRecord> doRecords, String backupDirPath, String targetDirPath) {
        try {
            List<PatchRecord> failRecords = new ArrayList<>();
            for (PatchRecord record :
                    doRecords) {
                Console.log("[回滚]: {}", record.getAction().getDescription(), record.getFile());
                String sourceResourcePath = FileUtil.getAbsolutePath(backupDirPath + "/" + record.getFile());
                String targetResourcePath = FileUtil.getAbsolutePath(targetDirPath + "/" + record.getFile());
                boolean succeed = false;
                switch (record.getAction()) {
                    case ADD:
                        succeed = FileUtil.del(targetResourcePath);
                        break;
                    case MODIFY:
                    case DELETE:
                        File file = FileUtil.copy(sourceResourcePath, targetResourcePath, true);
                        succeed = file.exists();
                        break;
                }
                if (!succeed) {
                    failRecords.add(record);
                }
            }
            Console.log("=====================================================================");
            for (PatchRecord record :
                    failRecords) {
                Console.log("[回滚]: {} -> 失败,请手动复制文件进行恢复", record.getFile());
            }
            Console.log("=====================================================================");
        } catch (Exception e) {
            e.printStackTrace();
            Console.log("回滚失败,尝试全量回滚或手动操作恢复!");
        }
    }

    private static boolean patch(String patchFile, String target) {
        File patch;
        if (!StrUtil.isBlank(patchFile)) {
            patch = new File(FileUtil.getAbsolutePath(patchFile));
        } else {
            patch = new File(FileUtil.getAbsolutePath("patch"));
        }
        Console.log("确认将 {} 内的更新补丁更新到 {}? Y(是) N(否)", patch.getAbsolutePath(), target);
        String input = Console.input();
        if (!"Y".equals(input)) {
            return false;
        }
        File patchDir = FileUtil.getParent(patch, 1);
        File resourceDir = new File(FileUtil.getAbsolutePath(patchDir.getAbsolutePath() + "/resource"));
        Assert.isTrue(resourceDir.exists(), "patch 文件对应的resource文件夹不存在,请选择程序生成的patch文件夹内的patch文件且不要修改目录结构");
        List<PatchRecord> records = PatchRecord.readRecords(patch);
        // backup target directory
        Console.log("[开始备份目标文件夹]: {}", target);
        File backupFile = FileUtil.copy(target, patchDir.getAbsolutePath(), true);
        Console.log("[备份完成]: {}", backupFile.getAbsolutePath());
        Console.log("[开始打补丁]", backupFile.getAbsolutePath());
        List<PatchRecord> failRecords = new ArrayList<>();
        for (PatchRecord record : records) {
            String sourceResourcePath = FileUtil.getAbsolutePath(resourceDir.getAbsolutePath() + "/" + record.getFile());
            String targetResourcePath = FileUtil.getAbsolutePath(target + "/" + record.getFile());
            boolean succeed = false;
            try {
                switch (record.getAction()) {
                    case ADD:
                    case MODIFY:
                        File file = FileUtil.copy(sourceResourcePath, targetResourcePath, true);
                        succeed = file.exists();
                        Console.log("[{}]: {} -> {}", record.getAction().getDescription(), sourceResourcePath, targetResourcePath);
                        break;
                    case DELETE:
                        succeed = FileUtil.del(targetResourcePath);
                        Console.log("[{}]: {}", record.getAction().getDescription(), targetResourcePath);
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!succeed) {
                failRecords.add(record);
                break;
            }
        }
        if (!failRecords.isEmpty()) {
            Console.log("=====================================================================");
            for (PatchRecord record :
                    failRecords) {
                Console.log("[{}]: {} -> 失败,请手动复制文件进行更新", record.getAction().getDescription(), record.getFile());
            }
            Console.log("=====================================================================");
        }
        return true;
    }

    public static void main(String[] args) throws IOException {
        Command command = new Command();
        JCommander.newBuilder().addObject(command).build().parse(args);
        if (!StrUtil.isBlank(command.getWarPath()) && command.isInit()) {
            String path = init(command.getWarPath());
            System.out.println("初始化完成,校验文件路径: " + path);
        } else if (!StrUtil.isBlank(command.getWarPath()) && !StrUtil.isBlank(command.getPatchFile())) {
            // -war and -patch
            String path = createNewPatch(command.getWarPath(), command.getPatchFile());
            Console.log(StrUtil.format("创建新的patch完成: {}", path));
        } else if (!StrUtil.isBlank(command.getPatchFile()) && !StrUtil.isBlank(command.getTarget())) {
            // -patch and -target
            boolean completable = patch(command.getPatchFile(), command.getTarget());
            Console.log("patch{}", completable ? "完成" : "取消");
        } else if (!StrUtil.isBlank(command.getTarget())) {
            // -target
            boolean completable = patch(null, command.getTarget());
            Console.log("patch{}", completable ? "完成" : "取消");
        } else if (!StrUtil.isBlank(command.getWarPath())) {
            // -war
            String path = createNewPatch(command.getWarPath(), null);
            Console.log(StrUtil.format("创建新的patch完成: {}", path));
        }
    }
}