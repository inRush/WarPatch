package cn.inrush;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.LineHandler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * patch 记录实体
 *
 * @author inRush
 * @date 2020/9/3
 */
public class PatchRecord {

    public enum Action {
        /**
         * 新增
         */
        ADD("a"),
        /**
         * 修改
         */
        MODIFY("m"),
        /**
         * 删除
         */
        DELETE("d");
        private String code;

        private Action(String code) {
            this.code = code;
        }

        public static Action fromCode(String code) {
            switch (code) {
                case "a":
                    return ADD;
                case "m":
                    return MODIFY;
                case "d":
                    return DELETE;
                default:
                    return null;
            }
        }
    }

    private String file;
    private Action action;

    public PatchRecord(String file, Action action) {
        this.file = file;
        this.action = action;
    }

    public PatchRecord() {
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    @Override
    public String toString() {
        return this.file.concat(";").concat(this.action.code);
    }

    public static class DeserializationHandler implements LineHandler {

        private final List<PatchRecord> records;

        public DeserializationHandler(List<PatchRecord> records) {
            this.records = records;
        }

        @Override
        public void handle(String s) {
            String[] temp = s.split(";");
            PatchRecord record = new PatchRecord();
            record.file = temp[0];
            record.action = Action.fromCode(temp[1]);
            this.records.add(record);
        }
    }

    public static List<PatchRecord> readRecords(File patchFile) {
        List<PatchRecord> records = new ArrayList<>();
        FileUtil.readLines(patchFile, StandardCharsets.UTF_8, new DeserializationHandler(records));
        return records;
    }
}
