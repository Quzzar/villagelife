package com.quzzar.villagelife.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.resources.ResourceLocation;

public class MultipleChoicePersonScreen extends ChoicePersonScreen {

    public static final int MAX_CHAR_IN_BOX = 32;

    private static final ResourceLocation GUI_TEXTURE = new ResourceLocation(Villagelife.MODID, "textures/container/person_screen_dialogue.png");

    private DialoguePage page;
    private String[] textLines;

    public MultipleChoicePersonScreen(RealPerson person, DialoguePage page) {
        super(person, GUI_TEXTURE);

        this.page = page;

        textLines = new String[ (int)(page.getText().length() / MAX_CHAR_IN_BOX) + 1 ];

        int lineNum = 0;
        textLines[0] = "";
        String[] words = page.getText().split("\\s+");
        for(String word : words){
            if(textLines[lineNum].length() + 1 + word.length() > MAX_CHAR_IN_BOX){
                lineNum++;
                textLines[lineNum] = word;
            } else {
                textLines[lineNum] += " "+word;
            }
        }
        textLines[0] = textLines[0].substring(1);

        

    }

    @Override
    protected void init() {
        super.init();

        for (int i = 0; i < page.getOptions().length; i++) {
            
            DialogueResult result = page.getOptions()[i].getResult();

            this.addRenderableWidget(new ImageButton(this.leftPos + 9, (this.height / 2) + 57 - (17*i), 158, 17, 0, 0, 17, DIALOGUE_CHOICE, (p_97863_) -> {
                // Use result
            }));

        }
        

    }

    @Override
    protected void renderLabels(PoseStack matrixStack, int x, int y) {
        super.renderLabels(matrixStack, x, y);

        float startingTextY = 86.0F;

        for(int i = 0; i < textLines.length; i++){
            this.font.draw(matrixStack, textLines[i], 12.0F, startingTextY + (10.0F*i), 4210752);
        }

        for (int i = 0; i < page.getOptions().length; i++) {
            this.font.draw(matrixStack, page.getOptions()[i].getText(), 14.0F, 144.0F - (17*i), 4210752);
        }


    }
    
}
