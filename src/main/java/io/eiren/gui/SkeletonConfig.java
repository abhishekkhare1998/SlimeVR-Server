package io.eiren.gui;

import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.event.MouseInputAdapter;

import io.eiren.gui.autobone.PoseRecorder;
import org.apache.commons.lang3.tuple.Pair;

import io.eiren.gui.autobone.AutoBone;
import io.eiren.gui.autobone.PoseFrame;
import io.eiren.gui.autobone.PoseRecordIO;
import io.eiren.util.StringUtils;
import io.eiren.util.ann.ThreadSafe;
import io.eiren.util.collections.FastList;
import io.eiren.vr.VRServer;
import io.eiren.vr.processor.HumanSkeletonWithLegs;
import io.eiren.vr.processor.HumanSkeleton;
import io.eiren.util.logging.LogManager;

public class SkeletonConfig extends EJBag {

	private final VRServer server;
	private final VRServerGUI gui;
	private final PoseRecorder poseRecorder;
	private final AutoBone autoBone;
	private Thread autoBoneThread = null;
	private Map<String, SkeletonLabel> labels = new HashMap<>();

	public SkeletonConfig(VRServer server, VRServerGUI gui) {
		super();
		this.server = server;
		this.gui = gui;
		this.poseRecorder = new PoseRecorder(server);
		this.autoBone = new AutoBone(server);

		setAlignmentY(TOP_ALIGNMENT);
		server.humanPoseProcessor.addSkeletonUpdatedCallback(this::skeletonUpdated);
		skeletonUpdated(null);
	}

	@ThreadSafe
	public void skeletonUpdated(HumanSkeleton newSkeleton) {
		java.awt.EventQueue.invokeLater(() -> {
			removeAll();

			int row = 0;

			add(new JCheckBox("Extended pelvis model") {{
				addItemListener(new ItemListener() {
				    @Override
				    public void itemStateChanged(ItemEvent e) {
				        if(e.getStateChange() == ItemEvent.SELECTED) {//checkbox has been selected
				        	if(newSkeleton != null && newSkeleton instanceof HumanSkeletonWithLegs) {
				        		HumanSkeletonWithLegs hswl = (HumanSkeletonWithLegs) newSkeleton;
				        		hswl.setSkeletonConfigBoolean("Extended pelvis model", true);
				        	}
				        } else {
				        	if(newSkeleton != null && newSkeleton instanceof HumanSkeletonWithLegs) {
				        		HumanSkeletonWithLegs hswl = (HumanSkeletonWithLegs) newSkeleton;
				        		hswl.setSkeletonConfigBoolean("Extended pelvis model", false);
				        	}
				        }
				    }
				});
				if(newSkeleton != null && newSkeleton instanceof HumanSkeletonWithLegs) {
	        		HumanSkeletonWithLegs hswl = (HumanSkeletonWithLegs) newSkeleton;
	        		setSelected(hswl.getSkeletonConfigBoolean("Extended pelvis model"));
				}
			}}, s(c(0, row, 1), 3, 1));
			row++;

			/*
			add(new JCheckBox("Extended knee model") {{
				addItemListener(new ItemListener() {
				    @Override
				    public void itemStateChanged(ItemEvent e) {
				        if(e.getStateChange() == ItemEvent.SELECTED) {//checkbox has been selected
				        	if(newSkeleton != null && newSkeleton instanceof HumanSkeletonWithLegs) {
				        		HumanSkeletonWithLegs hswl = (HumanSkeletonWithLegs) newSkeleton;
				        		hswl.setSkeletonConfigBoolean("Extended knee model", true);
				        	}
				        } else {
				        	if(newSkeleton != null && newSkeleton instanceof HumanSkeletonWithLegs) {
				        		HumanSkeletonWithLegs hswl = (HumanSkeletonWithLegs) newSkeleton;
				        		hswl.setSkeletonConfigBoolean("Extended knee model", false);
				        	}
				        }
				    }
				});
				if(newSkeleton != null && newSkeleton instanceof HumanSkeletonWithLegs) {
	        		HumanSkeletonWithLegs hswl = (HumanSkeletonWithLegs) newSkeleton;
	        		setSelected(hswl.getSkeletonConfigBoolean("Extended knee model"));
				}
			}}, s(c(0, row, 1), 3, 1));
			row++;
			//*/

			add(new TimedResetButton("Reset All", "All"), s(c(1, row, 1), 3, 1));
			add(new JButton("Auto") {{
				addMouseListener(new MouseInputAdapter() {
					@Override
					public void mouseClicked(MouseEvent e) {
						// Prevent running multiple times
						if (autoBoneThread != null) {
							return;
						}

						Thread thread = new Thread() {
							@Override
							public void run() {
								try {
									FastList<Pair<String, PoseFrame[]>> frameRecordings = new FastList<Pair<String, PoseFrame[]>>();

									File loadFolder = new File("LoadRecordings");
									if (loadFolder.isDirectory()) {
										setText("Load");

										File[] files = loadFolder.listFiles();
										if (files != null) {
											for (File file : files) {
												if (file.isFile() && org.apache.commons.lang3.StringUtils.endsWithIgnoreCase(file.getName(), ".abf")) {
													LogManager.log.info("[AutoBone] Detected recording at \"" + file.getPath() + "\", loading frames...");
													PoseFrame[] frames = PoseRecordIO.readFromFile(file);

													if (frames == null) {
														LogManager.log.severe("Reading frames from \"" + file.getPath() + "\" failed...");
													} else {
														frameRecordings.add(Pair.of(file.getName(), frames));
													}
												}
											}
										}
									}

									if (frameRecordings.size() > 0) {
										setText("Wait");
										LogManager.log.info("[AutoBone] Done loading frames!");
									} else {
										setText("Move");
										// 1000 samples at 20 ms per sample is 20 seconds
										int sampleCount = server.config.getInt("autobone.sampleCount", 1000);
										long sampleRate = server.config.getLong("autobone.sampleRateMs", 20L);
										Future<PoseFrame[]> framesFuture = poseRecorder.startFrameRecording(sampleCount, sampleRate);
										PoseFrame[] frames = framesFuture.get();
										LogManager.log.info("[AutoBone] Done recording!");

										setText("Wait");
										if (server.config.getBoolean("autobone.saveRecordings", true)) {
											File saveFolder = new File("Recordings");
											if (saveFolder.isDirectory() || saveFolder.mkdirs()) {
												File saveRecording;
												int recordingIndex = 1;
												do {
													saveRecording = new File(saveFolder, "ABRecording" + recordingIndex++ + ".abf");
												} while (saveRecording.exists());

												LogManager.log.info("[AutoBone] Exporting frames to \"" + saveRecording.getPath() + "\"...");
												if (PoseRecordIO.writeToFile(saveRecording, frames)) {
													LogManager.log.info("[AutoBone] Done exporting! Recording can be found at \"" + saveRecording.getPath() + "\".");
												} else {
													LogManager.log.severe("[AutoBone] Failed to export the recording to \"" + saveRecording.getPath() + "\".");
												}
											} else {
												LogManager.log.severe("[AutoBone] Failed to create the recording directory \"" + saveFolder.getPath() + "\".");
											}
										}
										frameRecordings.add(Pair.of("<Recording>", frames));
									}

									LogManager.log.info("[AutoBone] Processing frames...");
									FastList<Float> heightPercentError = new FastList<Float>(frameRecordings.size());
									for (Pair<String, PoseFrame[]> recording : frameRecordings) {
										LogManager.log.info("[AutoBone] Processing frames from \"" + recording.getKey() + "\"...");
										autoBone.reloadConfigValues();

										autoBone.minDataDistance = server.config.getInt("autobone.minimumDataDistance", autoBone.minDataDistance);
										autoBone.maxDataDistance = server.config.getInt("autobone.maximumDataDistance", autoBone.maxDataDistance);
										autoBone.numEpochs = server.config.getInt("autobone.epochCount", autoBone.numEpochs);
										autoBone.initialAdjustRate = server.config.getFloat("autobone.adjustRate", autoBone.initialAdjustRate);
										autoBone.adjustRateDecay = server.config.getFloat("autobone.adjustRateDecay", autoBone.adjustRateDecay);
										autoBone.slideErrorFactor = server.config.getFloat("autobone.slideErrorFactor", autoBone.slideErrorFactor);
										autoBone.offsetErrorFactor = server.config.getFloat("autobone.offsetErrorFactor", autoBone.offsetErrorFactor);
										autoBone.heightErrorFactor = server.config.getFloat("autobone.heightErrorFactor", autoBone.heightErrorFactor);

										boolean calcInitError = server.config.getBoolean("autobone.calculateInitialError", true);
										float targetHeight = server.config.getFloat("autobone.manualTargetHeight", -1f);
										heightPercentError.add(autoBone.processFrames(recording.getValue(), calcInitError, targetHeight));

										LogManager.log.info("[AutoBone] Done processing!");

										//#region Stats/Values
										Float neckLength = autoBone.configs.get("Neck");
										Float chestLength = autoBone.configs.get("Chest");
										Float waistLength = autoBone.configs.get("Waist");
										Float hipWidth = autoBone.configs.get("Hips width");
										Float legsLength = autoBone.configs.get("Legs length");
										Float kneeHeight = autoBone.configs.get("Knee height");

										float neckWaist = neckLength != null && waistLength != null ? neckLength / waistLength : 0f;
										float chestWaist = chestLength != null && waistLength != null ? chestLength / waistLength : 0f;
										float hipWaist = hipWidth != null && waistLength != null ? hipWidth / waistLength : 0f;
										float legWaist = legsLength != null && waistLength != null ? legsLength / waistLength : 0f;
										float kneeLeg = kneeHeight != null && legsLength != null ? kneeHeight / legsLength : 0f;

										LogManager.log.info("[AutoBone] Ratios: [{Neck-Waist: " + StringUtils.prettyNumber(neckWaist) +
										"}, {Chest-Waist: " + StringUtils.prettyNumber(chestWaist) +
										"}, {Hip-Waist: " + StringUtils.prettyNumber(hipWaist) +
										"}, {Leg-Waist: " + StringUtils.prettyNumber(legWaist) +
										"}, {Knee-Leg: " + StringUtils.prettyNumber(kneeLeg) + "}]");

										boolean first = true;
										StringBuilder configInfo = new StringBuilder("[");

										for (Entry<String, Float> entry : autoBone.configs.entrySet()) {
											if (!first) {
												configInfo.append(", ");
											} else {
												first = false;
											}

											configInfo.append("{" + entry.getKey() + ": " + StringUtils.prettyNumber(entry.getValue()) + "}");
										}

										configInfo.append(']');

										LogManager.log.info("[AutoBone] Length values: " + configInfo.toString());
									}

									if (heightPercentError.size() > 0) {
										float mean = 0f;
										for (float val : heightPercentError) {
											mean += val;
										}
										mean /= heightPercentError.size();

										float std = 0f;
										for (float val : heightPercentError) {
											float stdVal = val - mean;
											std += stdVal * stdVal;
										}
										std = (float)Math.sqrt(std / heightPercentError.size());

										LogManager.log.info("[AutoBone] Average height error: " + StringUtils.prettyNumber(mean, 6) + " (SD " + StringUtils.prettyNumber(std, 6) + ")");
									}
									//#endregion

									// Update GUI values after adjustment
									refreshAll();
								} catch (Exception e1) {
									LogManager.log.severe("[AutoBone] Failed adjustment!", e1);
								} finally {
									setText("Auto");
									autoBoneThread = null;
								}
							}
						};

						autoBoneThread = thread;
						thread.start();
					}
				});
			}}, s(c(4, row, 1), 3, 1));
			row++;

			add(new JLabel("Chest"), c(0, row, 1));
			add(new AdjButton("+", "Chest", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Chest"), c(2, row, 1));
			add(new AdjButton("-", "Chest", -0.01f), c(3, row, 1));
			add(new ResetButton("Reset", "Chest"), c(4, row, 1));
			row++;

			add(new JLabel("Waist"), c(0, row, 1));
			add(new AdjButton("+", "Waist", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Waist"), c(2, row, 1));
			add(new AdjButton("-", "Waist", -0.01f), c(3, row, 1));
			add(new TimedResetButton("Reset", "Waist"), c(4, row, 1));
			row++;

			add(new JLabel("Hips width"), c(0, row, 1));
			add(new AdjButton("+", "Hips width", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Hips width"), c(2, row, 1));
			add(new AdjButton("-", "Hips width", -0.01f), c(3, row, 1));
			add(new ResetButton("Reset", "Hips width"), c(4, row, 1));
			row++;

			add(new JLabel("Legs length"), c(0, row, 1));
			add(new AdjButton("+", "Legs length", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Legs length"), c(2, row, 1));
			add(new AdjButton("-", "Legs length", -0.01f), c(3, row, 1));
			add(new TimedResetButton("Reset", "Legs length"), c(4, row, 1));
			row++;

			add(new JLabel("Knee height"), c(0, row, 1));
			add(new AdjButton("+", "Knee height", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Knee height"), c(2, row, 1));
			add(new AdjButton("-", "Knee height", -0.01f), c(3, row, 1));
			add(new TimedResetButton("Reset", "Knee height"), c(4, row, 1));
			row++;

			add(new JLabel("Foot length"), c(0, row, 1));
			add(new AdjButton("+", "Foot length", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Foot length"), c(2, row, 1));
			add(new AdjButton("-", "Foot length", -0.01f), c(3, row, 1));
			add(new ResetButton("Reset", "Foot length"), c(4, row, 1));
			row++;

			add(new JLabel("Head offset"), c(0, row, 1));
			add(new AdjButton("+", "Head", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Head"), c(2, row, 1));
			add(new AdjButton("-", "Head", -0.01f), c(3, row, 1));
			add(new ResetButton("Reset", "Head"), c(4, row, 1));
			row++;

			add(new JLabel("Neck length"), c(0, row, 1));
			add(new AdjButton("+", "Neck", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Neck"), c(2, row, 1));
			add(new AdjButton("-", "Neck", -0.01f), c(3, row, 1));
			add(new ResetButton("Reset", "Neck"), c(4, row, 1));
			row++;

			add(new JLabel("Virtual waist"), c(0, row, 1));
			add(new AdjButton("+", "Virtual waist", 0.01f), c(1, row, 1));
			add(new SkeletonLabel("Virtual waist"), c(2, row, 1));
			add(new AdjButton("-", "Virtual waist", -0.01f), c(3, row, 1));
			add(new ResetButton("Reset", "Virtual waist"), c(4, row, 1));
			row++;

			gui.refresh();
		});
	}

	@ThreadSafe
	public void refreshAll() {
		java.awt.EventQueue.invokeLater(() -> {
			labels.forEach((joint, label) -> {
				label.setText(StringUtils.prettyNumber(server.humanPoseProcessor.getSkeletonConfig(joint) * 100, 0));
			});
		});
	}

	private void change(String joint, float diff) {
		float current = server.humanPoseProcessor.getSkeletonConfig(joint);
		server.humanPoseProcessor.setSkeletonConfig(joint, current + diff);
		server.saveConfig();
		labels.get(joint).setText(StringUtils.prettyNumber((current + diff) * 100, 0));
	}

	private void reset(String joint) {
		server.humanPoseProcessor.resetSkeletonConfig(joint);
		server.saveConfig();
		if(!"All".equals(joint)) {
			float current = server.humanPoseProcessor.getSkeletonConfig(joint);
			labels.get(joint).setText(StringUtils.prettyNumber((current) * 100, 0));
		} else {
			labels.forEach((jnt, label) -> {
				float current = server.humanPoseProcessor.getSkeletonConfig(jnt);
				label.setText(StringUtils.prettyNumber((current) * 100, 0));
			});
		}
	}

	private class SkeletonLabel extends JLabel {

		public SkeletonLabel(String joint) {
			super(StringUtils.prettyNumber(server.humanPoseProcessor.getSkeletonConfig(joint) * 100, 0));
			labels.put(joint, this);
		}
	}

	private class AdjButton extends JButton {

		public AdjButton(String text, String joint, float diff) {
			super(text);
			addMouseListener(new MouseInputAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					change(joint, diff);
				}
			});
		}
	}

	private class ResetButton extends JButton {

		public ResetButton(String text, String joint) {
			super(text);
			addMouseListener(new MouseInputAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					reset(joint);
				}
			});
		}
	}

	private class TimedResetButton extends JButton {

		public TimedResetButton(String text, String joint) {
			super(text);
			addMouseListener(new MouseInputAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					ButtonTimer.runTimer(TimedResetButton.this, 3, text, () -> reset(joint));
				}
			});
		}
	}
}
