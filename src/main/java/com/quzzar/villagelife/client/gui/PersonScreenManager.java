package com.quzzar.villagelife.client.gui;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.client.Minecraft;

public class PersonScreenManager {
    
    public static void openPersonScreen(RealPerson person, DialoguePage page){

        Minecraft.getInstance().setScreen(new MultipleChoicePersonScreen(person, page));

    }

    // temp
    public static void openTempScreen(RealPerson person){

        DialoguePage page = new DialoguePage("Gem Hey! How's it going? I think you're reallyu cool, ya know that?.",
            new DialogueOption("What do you do?", DialogueResult.GO_TO_DIALOGUE),
            new DialogueOption("Give gift.", DialogueResult.GO_TO_DIALOGUE)
        );

        openPersonScreen(person, page);

    }

}
