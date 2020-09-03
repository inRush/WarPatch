package cn.inrush.validator;

import java.io.File;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

import cn.hutool.core.util.StrUtil;

public class FileValidator implements IParameterValidator {

  @Override
  public void validate(String name, String value) throws ParameterException {
    File file = new File(value);
    if (!file.exists()) {
      throw new ParameterException(StrUtil.format("Parameter [{}({})] is not exists.", name, value));
    }
    if (file.isDirectory()) {
      throw new ParameterException(
          StrUtil.format("Parameter [{}({})] expect a file, but got a directory.", name, value));
    }
    if (!file.canWrite()) {
      throw new ParameterException(StrUtil.format("Parameter [{}({})] should be a writable.", name, value));
    }
  }

}
