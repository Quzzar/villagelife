package com.quzzar.villagelife.village.bookkeeping;

public class NoResourceBkEvent extends BkEvent {
  
  private MissingResource missingResource;

  public NoResourceBkEvent(MissingResource missingResource){
    super();

    this.missingResource = missingResource;

  }

  public MissingResource getMissingResource(){
    return missingResource;
  }

  public enum MissingResource {
    WATER,
    WOOD,
    STONE,
    IRON,
  }

}
