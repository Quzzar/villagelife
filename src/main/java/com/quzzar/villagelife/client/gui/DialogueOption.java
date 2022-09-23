package com.quzzar.villagelife.client.gui;

public class DialogueOption {
    
    private String text;
    private DialogueResult result;

    public DialogueOption(String text, DialogueResult result){
        this.text = text;
        this.result = result;
    }

    public String getText(){
        return text;
    }
    public DialogueResult getResult(){
        return result;
    }

}
