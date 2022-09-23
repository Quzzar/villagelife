package com.quzzar.villagelife.client.gui;

public class DialoguePage {
 
    private String text;
    private DialogueOption[] options;

    public DialoguePage(String text, DialogueOption ...options){
        this.text = text;
        this.options = options;
    }

    public String getText(){
        return text;
    }

    public DialogueOption[] getOptions(){
        return options;
    }

}
