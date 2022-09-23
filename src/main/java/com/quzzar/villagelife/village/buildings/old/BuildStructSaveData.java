package com.quzzar.villagelife.village.buildings.old;

/*

import java.util.ArrayList;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.utils.SerialPair;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class BuildStructSaveData extends SavedData {

    public static BuildStructSaveData load(CompoundTag tag){
        BuildStructSaveData data = new BuildStructSaveData();
        data.setProgress(tag.getInt("progress"));
        data.setProgress(tag.getInt("index"));
        data.setSeed(tag.getLong("seed"));
        data.setList1(tag.getByteArray("list1"));
        data.setList2(tag.getByteArray("list2"));
        data.setList3(tag.getByteArray("list3"));
        data.setI(tag.getInt("i"));
        data.setJ(tag.getInt("j"));
        data.setK(tag.getInt("k"));
        data.setL(tag.getInt("l"));
        data.setI1(tag.getInt("i1"));
        data.setJ1(tag.getInt("j1"));
        return data;
    }

    ///

    private int progress;// 0=not started, 1=in progress & working, 2=in progress & paused, 3=complete
    private int index;
    private long randSeed;
    private byte[] list1;
    private byte[] list2;
    private byte[] list3;
    private int i;
    private int j;
    private int k;
    private int l;
    private int i1;
    private int j1;

    public BuildStructSaveData() {
        super();
        progress = 0;
        index = 0;
        randSeed = 0;
        list1 = new byte[0];
        list2 = new byte[0];
        list3 = new byte[0];
        i = Integer.MAX_VALUE;
        j = Integer.MAX_VALUE;
        k = Integer.MAX_VALUE;
        l = Integer.MIN_VALUE;
        i1 = Integer.MIN_VALUE;
        j1 = Integer.MIN_VALUE;
    }

    public void setProgress(int progressNum){
        this.progress = progressNum;
    }
    public int getProgress(){
        return progress;
    }

    public void setIndex(int index){
        this.index = index;
    }
    public int getIndex(){
        return index;
    }

    public void setSeed(long randSeed){
        this.randSeed = randSeed;
    }
    public long getSeed(){
        return randSeed;
    }
    
    public void setList1(byte[] array){
        this.list1 = array;
    }
    public void setList1(ArrayList<Long> list){
        this.list1 = Utils.objectToByteArray(list);
    }
    public ArrayList<Long> getList1(){
        return Utils.byteArrayToGeneric(this.list1);
    }

    public void setList2(byte[] array){
        this.list2 = array;
    }
    public void setList2(ArrayList<Long> list){
        this.list2 = Utils.objectToByteArray(list);
    }
    public ArrayList<Long> getList2(){
        return Utils.byteArrayToGeneric(this.list2);
    }

    public void setList3(byte[] array){
        this.list3 = array;
    }
    public void setList3(ArrayList<SerialPair<Long, Boolean>> list){
        this.list3 = Utils.objectToByteArray(list);
    }
    public ArrayList<SerialPair<Long, Boolean>> getList3(){
        return Utils.byteArrayToGeneric(this.list3);
    }

    public void setI(int value){
        this.i = value;
    }
    public int getI(){
        return this.i;
    }
    
    public void setJ(int value){
        this.j = value;
    }
    public int getJ(){
        return this.j;
    }

    public void setK(int value){
        this.i = value;
    }
    public int getK(){
        return this.i;
    }

    public void setL(int value){
        this.l = value;
    }
    public int getL(){
        return this.l;
    }

    public void setI1(int value){
        this.i1 = value;
    }
    public int getI1(){
        return this.i1;
    }

    public void setJ1(int value){
        this.j1 = value;
    }
    public int getJ1(){
        return this.j1;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("progress", this.progress);
        tag.putInt("index", this.index);
        tag.putLong("seed", this.randSeed);
        tag.putByteArray("list1", this.list1);
        tag.putByteArray("list2", this.list2);
        tag.putByteArray("list3", this.list3);
        tag.putInt("i", this.i);
        tag.putInt("j", this.j);
        tag.putInt("k", this.k);
        tag.putInt("l", this.l);
        tag.putInt("i1", this.i1);
        tag.putInt("j1", this.j1);
        return tag;
    }
    
}

*/