package cn.inrush;

import com.beust.jcommander.Parameter;

import cn.inrush.validator.FileValidator;

public class Command {
    @Parameter(names = "-war", description = "war package location", validateWith = FileValidator.class)
    private String warPath;

    @Parameter(names = "-init", description = "init package message")
    private boolean init;

    @Parameter(names = "-patch", description = "patch file location", validateWith = FileValidator.class)
    private String patchFile;

    @Parameter(names = "-target", description = "patch target")
    private String target;

    public String getWarPath() {
        return this.warPath;
    }

    public void setWarPath(String warPath) {
        this.warPath = warPath;
    }

    public boolean isInit() {
        return this.init;
    }

    public boolean getInit() {
        return this.init;
    }

    public void setInit(boolean init) {
        this.init = init;
    }


    public String getPatchFile() {
        return patchFile;
    }

    public void setPatchFile(String patchFile) {
        this.patchFile = patchFile;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }
}
