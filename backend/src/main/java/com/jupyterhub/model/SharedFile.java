/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.model;

public class SharedFile {
    private String name;
    private String path;
    private long size;
    private String sizeFormatted;
    private String modifyTime;
    private String type;
    private boolean isDirectory;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getSizeFormatted() { return sizeFormatted; }
    public void setSizeFormatted(String sizeFormatted) { this.sizeFormatted = sizeFormatted; }

    public String getModifyTime() { return modifyTime; }
    public void setModifyTime(String modifyTime) { this.modifyTime = modifyTime; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isIsDirectory() { return isDirectory; }
    public void setIsDirectory(boolean isDirectory) { this.isDirectory = isDirectory; }
}
