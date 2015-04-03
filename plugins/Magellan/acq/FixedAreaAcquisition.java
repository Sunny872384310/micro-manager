/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package acq;

import autofocus.CrossCorrelationAutofocus;
import coordinates.XYStagePosition;
import gui.SettingsDialog;
import ij.IJ;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.micromanager.MMStudio;
import org.micromanager.utils.ReportingUtils;
import surfacesandregions.Point3d;

/**
 *
 * @author Henry
 */
public class FixedAreaAcquisition extends Acquisition {

   private volatile boolean paused_ = false;
   private FixedAreaAcquisitionSettings settings_;
   private int numTimePoints_;
   private List<XYStagePosition> positions_;
   private long nextTimePointStartTime_ms_;
   private ParallelAcquisitionGroup acqGroup_;
   //barrier to wait for event generation at successive time points
   //signals come from 1) event genreating thread 2) Parallel acq group
   private CyclicBarrier startNextTPBarrier_ = new CyclicBarrier(2);
   //barrier to wait for all images to be written before starting nex time point stuff
   //signals come from 1) event generating thread 2) tagged iamge sink
   private CyclicBarrier tpFinishedBarrier_ = new CyclicBarrier(2);
   //executor service to wait for next execution
   private ScheduledExecutorService waitForNextTPSerivice_ = Executors.newScheduledThreadPool(1);
   private ExecutorService eventGenerator_;
   private CrossCorrelationAutofocus autofocus_;
   private int maxSliceIndex_ = 0;

   /**
    * Acquisition with fixed XY positions (although they can potentially all be
    * translated between time points Supports time points Z stacks that can
    * change at positions between time points
    *
    * Acquisition engine manages a thread that reads events, fixed area
    * acquisition has another thread that generates events
    */
   public FixedAreaAcquisition(FixedAreaAcquisitionSettings settings, ParallelAcquisitionGroup acqGroup) throws Exception {
      super(settings.zStep_);
      acqGroup_ = acqGroup;
      settings_ = settings;
      eventGenerator_ = Executors.newSingleThreadExecutor(new ThreadFactory() {
         @Override
         public Thread newThread(Runnable r) {
            return new Thread(r, settings_.name_ + ": Event generator");
         }
      });
      readSettings();
      setupXYPositions();
      initialize(settings.dir_, settings.name_, settings.tileOverlap_);
      createEventGenerator();
      createAutofocus();
   }

   private void setupXYPositions() {
      try {
         //get XY positions
         if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SURFACE_FIXED_DISTANCE_Z_STACK) {
            positions_ = settings_.fixedSurface_.getXYPositions(settings_.tileOverlap_);
         } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.VOLUME_BETWEEN_SURFACES_Z_STACK) {
            positions_ = settings_.useTopOrBottomFootprint_ == FixedAreaAcquisitionSettings.FOOTPRINT_FROM_TOP
                    ? settings_.topSurface_.getXYPositions(settings_.tileOverlap_) : settings_.bottomSurface_.getXYPositions(settings_.tileOverlap_);
         } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SIMPLE_Z_STACK) {
            positions_ = settings_.footprint_.getXYPositions(settings_.tileOverlap_);
         } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.REGION_2D) {
            positions_ = settings_.footprint_.getXYPositions(settings_.tileOverlap_);
         } else {
            //no space mode, use current stage positon
            positions_ = new ArrayList<XYStagePosition>();
            int fullTileWidth = (int) MMStudio.getInstance().getCore().getImageWidth();
            int fullTileHeight = (int) MMStudio.getInstance().getCore().getImageHeight();
            int tileWidthMinusOverlap = fullTileWidth - this.getOverlapX();
            int tileHeightMinusOverlap = fullTileHeight - this.getOverlapY();
            positions_.add(new XYStagePosition(MMStudio.getInstance().getCore().getXYStagePosition(xyStage_),
                    tileWidthMinusOverlap, tileHeightMinusOverlap, fullTileWidth, fullTileHeight, 0, 0,
                    MMStudio.getInstance().getCore().getCurrentPixelSizeConfig()));
         }
      } catch (Exception e) {
         ReportingUtils.showError("Problem with Acquisition's XY positions. Check acquisition settings");
         throw new RuntimeException();
      }
   }
   
   private void createAutofocus() {
      if (settings_.autofocusEnabled_) {
         //convert channel name to channel index
         int cIndex = getAutofocusChannelIndex();     
         autofocus_ = new CrossCorrelationAutofocus(this,cIndex, settings_.autofocusMaxDisplacemnet_um_, settings_.autoFocusZDevice_);
      }
   }

   public int getAutofocusChannelIndex() {
       int cIndex = 0;
         if (settings_.channels_.size() == 0) {
            if (MMStudio.getInstance().getCore().getNumberOfCameraChannels() == 1) {
               cIndex = 0;
            } else if (SettingsDialog.getDemoMode()) {
               cIndex = Arrays.asList(new String[]{"Violet","Blue","Green","Yellow","Red","FarRed"}).indexOf(settings_.autofocusChannelName_);
            } else {
               //multichannel cam only
               for (int i = 0; i < MMStudio.getInstance().getCore().getNumberOfCameraChannels(); i++) {
                  if (MMStudio.getInstance().getCore().getCameraChannelName(i).equals(settings_.autofocusChannelName_)) {
                     cIndex = i;
                     break;
                  }
                  if (i == MMStudio.getInstance().getCore().getNumberOfCameraChannels() -1) {
                     ReportingUtils.showError("Couldn't find channel: " + settings_.autofocusChannelName_ + ", Aborting autofocus");
                  }
               }
            }
         } else {
             //TODO:
         }   
            return cIndex;
   }
   
   public int getMaxSliceIndex() {
      return maxSliceIndex_;
   }
   
   public FixedAreaAcquisitionSettings getSettings() {
      return settings_;
   }

   private void readSettings() {
      numTimePoints_ = settings_.timeEnabled_ ? settings_.numTimePoints_ : 1;
   }

   public boolean isPaused() {
      return paused_;
   }

   public double getTimeInterval_ms() {
      return settings_.timePointInterval_ * (settings_.timeIntervalUnit_ == 1 ? 1000 : (settings_.timeIntervalUnit_ == 2 ? 60000 : 1));
   }

   public int getNumRows() {
      int maxIndex = 0;
      synchronized (positions_) {
         for (XYStagePosition p : positions_) {
            maxIndex = Math.max(maxIndex, p.getGridRow());
         }
      }
      return maxIndex + 1;
   }

   public int getNumColumns() {
      int maxIndex = 0;
      for (XYStagePosition p : positions_) {
         maxIndex = Math.max(maxIndex, p.getGridCol());
      }
      return maxIndex + 1;
   }

   public long getNextWakeTime_ms() {
      return nextTimePointStartTime_ms_;
   }

   /**
    * abort acquisition. Block until successfully finished
    */
   public void abort() {
      
      if (finished_) {
         //acq already aborted
         return;
      }
      //interrupt event generating thread
      eventGenerator_.shutdownNow();
      try {
         //wait for it to exit
         while (!eventGenerator_.awaitTermination(5, TimeUnit.MILLISECONDS)) {}
      } catch (InterruptedException ex) {
         ReportingUtils.showError("Unexpected interrupt whil trying to abort acquisition");
         //shouldn't happen
      }
      //clear any pending events, specific to this acqusition (since parallel acquisitions
      //share their event queue
      events_.clear();
      try {
         //add finishing events to shoutdown all the downstream stuff          
         events_.put(AcquisitionEvent.createAcquisitionFinishedEvent(this));
      } catch (InterruptedException ex) {
         ReportingUtils.showError("Unexpected interrupted exception while trying to abort"); //shouldnt happen
      }

      
      acqGroup_.signalAborted(this);
      //singal aborted should wait for the image sink to die so this function doesnt return until abort complete
      
            //make sure parallel group doesnt hang waiting to signal this acq to start next TP
      startNextTPBarrier_.reset();
   }
   
   public void signalReadyForNextTP() throws InterruptedException, BrokenBarrierException {
      //called by event generating thread and and parallel manager thread to
     //ensure enough time has passed to start next TP and that parallel group allows it 
      startNextTPBarrier_.await();
   }
   
   /**
    * called by image sink when acquisition is finsihed or aborted
    */
   public void allImagesFinishedWriting() {
      if (tpFinishedBarrier_.getNumberWaiting() == 1) {
         try {
            //release event generator if needed
            tpFinishedBarrier_.await();
         } catch (Exception ex) {
            ReportingUtils.showError("Unexpected interrupt while wiating for acqusition finished");
         }
      }
   }
   
   /**
    * Called by image sink at the end of writing images of each time point
    */
   public void timepointImagesFinishedWriting() {
      try {
         tpFinishedBarrier_.await();
         //these exception should never happen because event generating thread will always be awaiting first
      } catch (InterruptedException ex) {
         ReportingUtils.showError("Image sink interrupeted");
      } catch (BrokenBarrierException ex) {
         ReportingUtils.showError("Image sink broken barrier");
      }
   }

   private void pauseUntilReadyForTP() throws InterruptedException {
      try {
         //Pause here bfore next time point starts
         long timeUntilNext = nextTimePointStartTime_ms_ - System.currentTimeMillis();
         if (timeUntilNext > 0) {
            //wait for enough time to pass and parallel group to signal ready
            ScheduledFuture future = waitForNextTPSerivice_.schedule(new Runnable() {

               @Override
               public void run() {
                  try {
                     startNextTPBarrier_.await();                     
                  } catch (Exception ex) {
                     throw new RuntimeException(); //propogate interrupt
                  }
               }
            }, timeUntilNext, TimeUnit.MILLISECONDS);
            future.get();
         } else {
            //already enough time passed, just wait for go-ahead from parallel group
            startNextTPBarrier_.await();
         }
      } catch (BrokenBarrierException e) {
         throw new InterruptedException(); //acq aborted
      } catch (ExecutionException ex) {
         throw new InterruptedException(); //acq aborted         
      }
   }

   private void createEventGenerator() {
      eventGenerator_.submit(new Runnable() {
         //check interupt status before any blocking call is entered
         @Override
         public void run() {
            try {
               nextTimePointStartTime_ms_ = 0;
               for (int timeIndex = 0; timeIndex < numTimePoints_; timeIndex++) {
                  if (Thread.interrupted()) {
                     throw new InterruptedException();
                  }
                  
                  pauseUntilReadyForTP();         
                  //set autofocus position
                  if (autofocus_ != null && timeIndex > 1) {
                     events_.put(AcquisitionEvent.createAutofocusEvent(settings_.autoFocusZDevice_, autofocus_.getAutofocusPosition()));
                  } else if (autofocus_ != null && timeIndex <= 1 && settings_.setInitialAutofocusPosition_) {
                     events_.put(AcquisitionEvent.createAutofocusEvent(settings_.autoFocusZDevice_, settings_.initialAutofocusPosition_));
                  }
                  //set the next time point start time
                  double interval_ms = settings_.timePointInterval_ * (settings_.timeIntervalUnit_ == 1 ? 1000 : (settings_.timeIntervalUnit_ == 2 ? 60000 : 1));
                  nextTimePointStartTime_ms_ = (long) (System.currentTimeMillis() + interval_ms);

                  for (int positionIndex = 0; positionIndex < positions_.size(); positionIndex++) {
                     //add events for all slices/channels at this position
                     XYStagePosition position = positions_.get(positionIndex);

                     int channelIndex = 0; //TODO: channels

                     //TODO: check signs for all of these
                     //get highest possible z position to image, which is slice index 0
                     double zTop = getZTopCoordinate();
                     int sliceIndex = -1;
                     while (true) {
                        sliceIndex++;
                        double zPos = zTop + sliceIndex * zStep_;
                        if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.REGION_2D) {
                           //2D region
                           if (sliceIndex > 0) {
                              break;
                           }
                        } else {
                           //3D region
                           if (isZAboveImagingVolume(position, zPos) || (zStageHasLimits_ && zPos < zStageLowerLimit_)) {
                              continue; //position is above imaging volume or range of focus device
                           }
                           if (isZBelowImagingVolume(position, zPos) || (zStageHasLimits_ && zPos > zStageUpperLimit_)) {
                              //position is below z stack or limit of focus device, z stack finished
                              break;
                           }
                        }
                        AcquisitionEvent event = new AcquisitionEvent(FixedAreaAcquisition.this, timeIndex, channelIndex, sliceIndex,
                                positionIndex, zPos, position, settings_.propPairings_);                        
                        if (Thread.interrupted()) {
                           throw new InterruptedException();
                        }
                        //keep track of biggest slice index
                        maxSliceIndex_ = Math.max(maxSliceIndex_, event.sliceIndex_);
                        events_.put(event); //event generator will block if event queueu is full
                     }
                  } //position loop finished

                  if (timeIndex == numTimePoints_ - 1) {
                     //acquisition now finished, add event with null acq field so engine will mark acquisition as finished                    
                     events_.put(AcquisitionEvent.createAcquisitionFinishedEvent(FixedAreaAcquisition.this));
                  } else {
                     events_.put(AcquisitionEvent.createTimepointFinishedEvent(FixedAreaAcquisition.this));
                  }
                  

                  //wait for final image of timepoint to be written before beginning end of timepoint stuff
                  if (Thread.interrupted()) {
                     throw new InterruptedException();
                  }
                  tpFinishedBarrier_.await();
                  //this call starts a new thread to not hang up cyclic barriers   
                  //signal to next acquisition in parallel group to start generating events, then continue using the event generator thread
                  //to calculate autofocus
                  acqGroup_.finishedTPEventGeneration(FixedAreaAcquisition.this);

                  //all images finished writing--can now run autofocus
                  if (autofocus_ != null) {
                     try {
                        autofocus_.run(timeIndex);
                     } catch (Exception ex) {
                        
                        IJ.log("Problem running autofocus " + ex.getMessage());
                     }
                  }
               }
               //acqusiition has generated all of its events
               eventGenerator_.shutdown();
            } catch (BrokenBarrierException ex) {
               ReportingUtils.showError("Unexpected broken barrier exception in event generator");
               ex.printStackTrace();
               return; //acq aborted
            } catch (InterruptedException e) {
               return; //acq aborted
            } finally {
               eventGenerator_.shutdown();
               waitForNextTPSerivice_.shutdown();
            }
         }
      }); 
   }


   /**
    * This function and the one below determine which slices will be collected
    * for a given position
    *
    * @param position
    * @param zPos
    * @return
    */
   private boolean isZAboveImagingVolume(XYStagePosition position, double zPos) throws InterruptedException {
      if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SURFACE_FIXED_DISTANCE_Z_STACK) {
         return settings_.fixedSurface_.isPositionCompletelyAboveSurface(position, settings_.fixedSurface_, zPos + settings_.distanceAboveFixedSurface_);
      } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.VOLUME_BETWEEN_SURFACES_Z_STACK) {
         return settings_.topSurface_.isPositionCompletelyAboveSurface(position, settings_.topSurface_, zPos + settings_.distanceAboveTopSurface_);
      } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SIMPLE_Z_STACK) {
         return zPos < settings_.zStart_;
      } else {
         //no zStack
         throw new RuntimeException(); //TODO: something better
      }
   }

   private boolean isZBelowImagingVolume(XYStagePosition position, double zPos) throws InterruptedException {
      if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SURFACE_FIXED_DISTANCE_Z_STACK) {
         return settings_.fixedSurface_.isPositionCompletelyBelowSurface(position,settings_.fixedSurface_, zPos - settings_.distanceBelowFixedSurface_);
      } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.VOLUME_BETWEEN_SURFACES_Z_STACK) {
         return settings_.bottomSurface_.isPositionCompletelyBelowSurface(position,settings_.bottomSurface_, zPos - settings_.distanceBelowBottomSurface_);
      } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SIMPLE_Z_STACK) {
         return zPos > settings_.zEnd_;
      } else {
         //no zStack
         throw new RuntimeException(); //TODO: something better
      }
   }
   //TODO: check sign of Z
   private double getZTopCoordinate() {
      if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SURFACE_FIXED_DISTANCE_Z_STACK) {
         Point3d[] interpPoints = settings_.fixedSurface_.getPoints();
         return interpPoints[0].z - settings_.distanceAboveFixedSurface_;
      } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.VOLUME_BETWEEN_SURFACES_Z_STACK) {
         Point3d[] interpPoints = settings_.topSurface_.getPoints();
         return interpPoints[0].z - settings_.distanceAboveTopSurface_;
      } else if (settings_.spaceMode_ == FixedAreaAcquisitionSettings.SIMPLE_Z_STACK) {
         return settings_.zStart_;
      } else {
         //region2D or no region
         return zOrigin_;
      }
   }

   @Override
   public double getZCoordinateOfSlice(int sliceIndex, int frameIndex) {
      return getZTopCoordinate() + sliceIndex  * zStep_;
   }

   @Override
   public int getSliceIndexFromZCoordinate(double z, int frameIndex) {
      return (int) Math.round((z - getZTopCoordinate()) / zStep_);
   }

   @Override
   protected JSONArray createInitialPositionList() {
      JSONArray pList = new JSONArray();
      for (XYStagePosition xyPos : positions_) {
         pList.put(xyPos.getMMPosition());
      }
      return pList;
   }

}