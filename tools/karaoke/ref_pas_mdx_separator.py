"""Module for separating audio sources using 
MDX architecture models."""

import os
import
 platform
import torch
import onnx
import onn
xruntime as ort
import numpy as np
import onn
x2torch
from tqdm import tqdm
from audio_sepa
rator.separator.uvr_lib_v5 import spec_utils

from audio_separator.separator.uvr_lib_v5.stf
t import STFT
from audio_separator.separator.
common_separator import CommonSeparator


cla
ss MDXSeparator(CommonSeparator):
    """
   
 MDXSeparator is responsible for separating a
udio sources using MDX models.
    It initial
izes with configuration parameters and prepar
es the model for separation tasks.
    """

 
   def __init__(self, common_config, arch_con
fig):
        # Any configuration values whic
h can be shared between architectures should 
be set already in CommonSeparator,
        # 
e.g. user-specified functionality choices (se
lf.output_single_stem) or common model parame
ters (self.primary_stem_name)
        super()
.__init__(config=common_config)

        # In
itializing user-configurable parameters, pass
ed through with an mdx_from the CLI or Separa
tor instance

        # Pick a segment size t
o balance speed, resource use, and quality:
 
       # - Smaller sizes consume less resourc
es.
        # - Bigger sizes consume more res
ources, but may provide better results.
     
   # - Default size is 256. Quality can chang
e based on your pick.
        self.segment_si
ze = arch_config.get("segment_size")

       
 # This option controls the amount of overlap
 between prediction windows.
        #  - Hig
her values can provide better results, but wi
ll lead to longer processing times.
        #
  - For Non-MDX23C models: You can choose bet
ween 0.001-0.999
        self.overlap = arch_
config.get("overlap")

        # Number of ba
tches to be processed at a time.
        # - 
Higher values mean more RAM usage but slightl
y faster processing times.
        # - Lower 
values mean less RAM usage but slightly longe
r processing times.
        # - Batch size va
lue has no effect on output quality.
        
# BATCH_SIZE = ('1', ''2', '3', '4', '5', '6'
, '7', '8', '9', '10')
        self.batch_siz
e = arch_config.get("batch_size", 1)

       
 # hop_length is equivalent to the more commo
nly used term "stride" in convolutional neura
l networks
        # In machine learning, par
ticularly in the context of convolutional neu
ral networks (CNNs),
        # the term "stri
de" refers to the number of pixels by which w
e move the filter across the input image.
   
     # Strides are a crucial component in the
 convolution operation, a fundamental buildin
g block of CNNs used primarily in the field o
f computer vision.
        # Stride is a para
meter that dictates the movement of the kerne
l, or filter, across the input data, such as 
an image.
        # When performing a convolu
tion operation, the stride determines how man
y units the filter shifts at each step.
     
   # The choice of stride affects the model i
n several ways:
        # Output Size: A larg
er stride will result in a smaller output spa
tial dimension.
        # Computational Effic
iency: Increasing the stride can decrease the
 computational load.
        # Field of View:
 A higher stride means that each step of the 
filter takes into account a wider area of the
 input image.
        #   This can be benefic
ial when the model needs to capture more glob
al features rather than focusing on finer det
ails.
        self.hop_length = arch_config.g
et("hop_length")

        # If enabled, model
 will be run twice to reduce noise in output 
audio.
        self.enable_denoise = arch_con
fig.get("enable_denoise")

        self.logge
r.debug(f"MDX arch params: batch_size={self.b
atch_size}, segment_size={self.segment_size}"
)
        self.logger.debug(f"MDX arch params
: overlap={self.overlap}, hop_length={self.ho
p_length}, enable_denoise={self.enable_denois
e}")

        # Initializing model-specific p
arameters from model_data JSON
        self.c
ompensate = self.model_data["compensate"]
   
     self.dim_f = self.model_data["mdx_dim_f_
set"]
        self.dim_t = 2 ** self.model_da
ta["mdx_dim_t_set"]
        self.n_fft = self
.model_data["mdx_n_fft_scale_set"]
        se
lf.config_yaml = self.model_data.get("config_
yaml", None)

        self.logger.debug(f"MDX
 arch params: compensate={self.compensate}, d
im_f={self.dim_f}, dim_t={self.dim_t}, n_fft=
{self.n_fft}")
        self.logger.debug(f"MD
X arch params: config_yaml={self.config_yaml}
")

        # In UVR, these variables are set
 but either aren't useful or are better handl
ed in audio-separator.
        # Leaving thes
e comments explaining to help myself or futur
e developers understand why these aren't in a
udio-separator.

        # "chunks" is not ac
tually used for anything in UVR...
        # 
self.chunks = 0

        # "adjust" is hard-c
oded to 1 in UVR, and only used as a multipli
er in run_model, so it does nothing.
        
# self.adjust = 1

        # "hop" is hard-co
ded to 1024 in UVR. We have a "hop_length" pa
rameter instead
        # self.hop = 1024

  
      # "margin" maps to sample rate and is s
et from the GUI in UVR (default: 44100). We h
ave a "sample_rate" parameter instead.
      
  # self.margin = 44100

        # "dim_c" is
 hard-coded to 4 in UVR, seems to be a parame
ter for the number of channels, and is only u
sed for checkpoint models.
        # We haven
't implemented support for the checkpoint mod
els here, so we're not using it.
        # se
lf.dim_c = 4

        self.load_model()

    
    self.n_bins = 0
        self.trim = 0
   
     self.chunk_size = 0
        self.gen_siz
e = 0
        self.stft = None

        self.
primary_source = None
        self.secondary_
source = None
        self.audio_file_path = 
None
        self.audio_file_base = None

   
 def load_model(self):
        """
        Lo
ad the model into memory from file on disk, i
nitialize it with config from the model data,

        and prepare for inferencing using ha
rdware accelerated Torch device.
        """

        self.logger.debug("Loading ONNX model
 for inference...")

        if self.segment_
size == self.dim_t:
            self.uses_pyt
orch_inference = False
            ort_sessio
n_options = ort.SessionOptions()
            
if self.log_level > 10:
                ort_s
ession_options.log_severity_level = 3
       
     else:
                ort_session_option
s.log_severity_level = 0

            ort_inf
erence_session = ort.InferenceSession(self.mo
del_path, providers=self.onnx_execution_provi
der, sess_options=ort_session_options)
      
      session_providers = ort_inference_sessi
on.get_providers()

            requested_pro
vider = self.onnx_execution_provider[0] if se
lf.onnx_execution_provider else None
        
    if requested_provider and requested_provi
der not in session_providers:
               
 self.logger.warning(
                    f"O
NNX Runtime could not activate requested prov
ider {requested_provider}; "
                
    f"session is using {session_providers}. T
his usually means required CUDA/cuDNN "
     
               f"runtime libraries are not vi
sible to the dynamic loader."
               
 )
            else:
                self.log
ger.debug(f"ONNX Runtime session providers: {
session_providers}")

            self.model_
run = lambda spek: ort_inference_session.run(
None, {"input": spek.cpu().numpy()})[0]
     
       self.logger.debug("Model loaded succes
sfully using ONNXruntime inferencing session.
")
        else:
            if platform.syst
em() == 'Windows':
                onnx_model
 = onnx.load(self.model_path)
               
 self.model_run = onnx2torch.convert(onnx_mod
el)
            else:
                self.mo
del_run = onnx2torch.convert(self.model_path)

   
            self.model_run.to(self.torch
_device).eval()
            self.logger.warni
ng("Model converted from onnx to pytorch due 
to segment size not matching dim_t, processin
g may be slower.")

    def separate(self, au
dio_file_path, custom_output_names=None):
   
     """
        Separates the audio file int
o primary and secondary sources based on the 
model's configuration.
        It processes t
he mix, demixes it into sources, normalizes t
he sources, and saves the output files.

    
    Args:
            audio_file_path (str): 
The path to the audio file to be processed.
 
           custom_output_names (dict, optiona
l): Custom names for the output files. Defaul
ts to None.

        Returns:
            lis
t: A list of paths to the output files genera
ted by the separation process.
        """
  
      self.audio_file_path = audio_file_path

        self.audio_file_base = os.path.splite
xt(os.path.basename(audio_file_path))[0]

   
     # Prepare the mix for processing
       
 self.logger.debug(f"Preparing mix for input 
audio file {self.audio_file_path}...")
      
  mix = self.prepare_mix(self.audio_file_path
)

        self.logger.debug("Normalizing mix
 before demixing...")
        peak = np.abs(m
ix).max()
        mix = spec_utils.normalize(
wave=mix, max_peak=self.normalization_thresho
ld, min_peak=self.amplification_threshold)

 
       # Start the demixing process
        s
ource = self.demix(mix) * peak
        self.l
ogger.debug("Demixing completed.")


        
if not isinstance(self.primary_source, np.nda
rray):
            self.primary_source = sour
ce.T

        # In UVR, the source is cached 
here if it's a vocal split model, but we're n
ot supporting that yet

        # Initialize 
the list for output files
        output_file
s = []
        self.logger.debug("Processing 
output files...")

        # Process the seco
ndary source if not already an array
        
if not isinstance(self.secondary_source, np.n
darray):
            self.logger.debug("Produ
cing secondary source: demixing in match_mix 
mode")
            raw_mix = self.demix(mix, 
is_match_mix=True)

            if self.inver
t_using_spec:
                self.logger.deb
ug("Inverting secondary stem using spectogram
 as invert_using_spec is set to True")
      
          self.secondary_source = spec_utils.
invert_stem(raw_mix, self.primary_source * se
lf.compensate)
            else:
            
    self.logger.debug("Inverting secondary st
em by subtracting of transposed demixed stem 
from transposed original mix")
              
  self.secondary_source = (-self.primary_sour
ce * self.compensate) + mix.T

        # Save
 and process the secondary stem if needed
   
     if not self.output_single_stem or self.o
utput_single_stem.lower() == self.secondary_s
tem_name.lower():
            self.secondary_
stem_output_path = self.get_stem_output_path(
self.secondary_stem_name, custom_output_names
)

            self.logger.info(f"Saving {sel
f.secondary_stem_name} stem to {self.secondar
y_stem_output_path}...")
            self.fin
al_process(self.secondary_stem_output_path, s
elf.secondary_source, self.secondary_stem_nam
e)
            output_files.append(self.secon
dary_stem_output_path)

        # Save and pr
ocess the primary stem if needed
        if n
ot self.output_single_stem or self.output_sin
gle_stem.lower() == self.primary_stem_name.lo
wer():
            self.primary_stem_output_p
ath = self.get_stem_output_path(self.primary_
stem_name, custom_output_names)
            s
elf.logger.info(f"Saving {self.primary_stem_n
ame} stem to {self.primary_stem_output_path}.
..")
            self.final_process(self.prim
ary_stem_output_path, self.primary_source, se
lf.primary_stem_name)
            output_file
s.append(self.primary_stem_output_path)

    
    # Not yet implemented from UVR features:

        # self.process_vocal_split_chain(seco
ndary_sources)
        # self.logger.debug("V
ocal split chain processed.")

        return
 output_files

    def initialize_model_setti
ngs(self):
        """
        This function 
sets up the necessary parameters for the mode
l, like the number of frequency bins (n_bins)
, the trimming size (trim),
        the size 
of each audio chunk (chunk_size), and the win
dow function for spectral transformations (wi
ndow).
        It ensures that the model is c
onfigured with the correct settings for proce
ssing the audio data.
        """
        sel
f.logger.debug("Initializing model settings..
.")

        # n_bins is half the FFT size pl
us one (self.n_fft // 2 + 1).
        self.n_
bins = self.n_fft // 2 + 1

        # trim is
 half the FFT size (self.n_fft // 2).
       
 self.trim = self.n_fft // 2

        # chunk
_size is the hop_length size times the segmen
t size minus one
        self.chunk_size = se
lf.hop_length * (self.segment_size - 1)

    
    # gen_size is the chunk size minus twice 
the trim size
        self.gen_size = self.ch
unk_size - 2 * self.trim

        self.stft =
 STFT(self.logger, self.n_fft, self.hop_lengt
h, self.dim_f, self.torch_device)

        se
lf.logger.debug(f"Model input params: n_fft={
self.n_fft} hop_length={self.hop_length} dim_
f={self.dim_f}")
        self.logger.debug(f"
Model settings: n_bins={self.n_bins}, trim={s
elf.trim}, chunk_size={self.chunk_size}, gen_
size={self.gen_size}")

    def initialize_mi
x(self, mix, is_ckpt=False):
        """
    
    After prepare_mix segments the audio, ini
tialize_mix further processes each segment.
 
       It ensures each audio segment is in th
e correct format for the model, applies neces
sary padding,
        and converts the segmen
ts into tensors for processing with the model
.
        This step is essential for preparin
g the audio data in a format that the neural 
network can process.
        """
        # Lo
g the initialization of the mix and whether c
heckpoint mode is used
        self.logger.de
bug(f"Initializing mix with is_ckpt={is_ckpt}
. Initial mix shape: {mix.shape}")

        #
 Ensure the mix is a 2-channel (stereo) audio
 signal
        if mix.shape[0] != 2:
       
     error_message = f"Expected a 2-channel a
udio signal, but got {mix.shape[0]} channels"

            self.logger.error(error_message)

            raise ValueError(error_message)


        # If in checkpoint mode, process the
 mix differently
        if is_ckpt:
        
    self.logger.debug("Processing in checkpoi
nt mode...")
            # Calculate padding 
based on the generation size and trim
       
     pad = self.gen_size + self.trim - (mix.s
hape[-1] % self.gen_size)
            self.lo
gger.debug(f"Padding calculated: {pad}")
    
        # Add padding at the beginning and th
e end of the mix
            mixture = np.con
catenate(
                (
                 
   np.zeros((2, self.trim), dtype="float32"),
  # Pad at the start
                    mix,

                    np.zeros((2, pad), dtype
="float32"),        # Pad in the middle (to m
atch chunk size)
                    np.zeros
((2, self.trim), dtype="float32"),  # Pad at 
the end
                ),
                1

            )
            # Determine the num
ber of chunks based on the mixture's length
 
           num_chunks = mixture.shape[-1] // 
self.gen_size
            self.logger.debug(f
"Mixture shape after padding: {mixture.shape}
, Number of chunks: {num_chunks}")
          
  # Split the mixture into chunks
           
 mix_waves = [mixture[:, i * self.gen_size : 
i * self.gen_size + self.chunk_size] for i in
 range(num_chunks)]
        else:
           
 # If not in checkpoint mode, process normall
y
            self.logger.debug("Processing i
n non-checkpoint mode...")
            mix_wa
ves = []
            n_sample = mix.shape[1]

            # Calculate necessary padding to 
make the total length divisible by the genera
tion size
            pad = self.gen_size - n
_sample % self.gen_size
            self.logg
er.debug(f"Number of samples: {n_sample}, Pad
ding calculated: {pad}")
            # Apply 
padding to the mix
            mix_p = np.con
catenate((np.zeros((2, self.trim)), mix, np.z
eros((2, pad)), np.zeros((2, self.trim))), 1)

            self.logger.debug(f"Shape of mix
 after padding: {mix_p.shape}")

            
# Process the mix in chunks
            i = 0

            while i < n_sample + pad:
      
          waves = np.array(mix_p[:, i : i + s
elf.chunk_size])
                mix_waves.ap
pend(waves)
                self.logger.debug
(f"Processed chunk {len(mix_waves)}: Start {i
}, End {i + self.chunk_size}")
              
  i += self.gen_size

        # Convert the l
ist of wave chunks into a tensor for processi
ng on the specified device
        mix_waves_
tensor = torch.tensor(mix_waves, dtype=torch.
float32).to(self.torch_device)
        self.l
ogger.debug(f"Converted mix_waves to tensor. 
Tensor shape: {mix_waves_tensor.shape}")

   
     return mix_waves_tensor, pad

    def de
mix(self, mix, is_match_mix=False):
        "
""
        Demixes the input mix into its con
stituent sources. If is_match_mix is True, th
e function adjusts the processing
        to 
better match the mix, affecting chunk sizes a
nd overlaps. The demixing process involves pa
dding the mix,
        processing it in chunk
s, applying windowing for overlaps, and accum
ulating the results to separate the sources.

        """
        self.logger.debug(f"Start
ing demixing process with is_match_mix: {is_m
atch_mix}...")
        self.initialize_model_
settings()

        # Preserves the original 
mix for later use.
        # In UVR, this is 
used for the pitch fix and VR denoise process
es, which aren't yet implemented here.
      
  org_mix = mix
        self.logger.debug(f"O
riginal mix stored. Shape: {org_mix.shape}")


        # Initializes a list to store the se
parated waveforms.
        tar_waves_ = []

 
       # Handling different chunk sizes and o
verlaps based on the matching requirement.
  
      if is_match_mix:
            # Sets a s
maller chunk size specifically for matching t
he mix.
            chunk_size = self.hop_len
gth * (self.segment_size - 1)
            # S
ets a small overlap for the chunks.
         
   overlap = 0.02
            self.logger.deb
ug(f"Chunk size for matching mix: {chunk_size
}, Overlap: {overlap}")
        else:
       
     # Uses the regular chunk size defined in
 model settings.
            chunk_size = sel
f.chunk_size
            # Uses the overlap s
pecified in the model settings.
            o
verlap = self.overlap
            self.logger
.debug(f"Standard chunk size: {chunk_size}, O
verlap: {overlap}")

        # Calculates the
 generated size after subtracting the trim fr
om both ends of the chunk.
        gen_size =
 chunk_size - 2 * self.trim
        self.logg
er.debug(f"Generated size calculated: {gen_si
ze}")

        # Calculates padding to make t
he mix length a multiple of the generated siz
e.
        pad = gen_size + self.trim - ((mix
.shape[-1]) % gen_size)
        # Prepares th
e mixture with padding at the beginning and t
he end.
        mixture = np.concatenate((np.
zeros((2, self.trim), dtype="float32"), mix, 
np.zeros((2, pad), dtype="float32")), 1)
    
    self.logger.debug(f"Mixture prepared with
 padding. Mixture shape: {mixture.shape}")

 
       # Calculates the step size for process
ing chunks based on the overlap.
        step
 = int((1 - overlap) * chunk_size)
        se
lf.logger.debug(f"Step size for processing ch
unks: {step} as overlap is set to {overlap}."
)

        # Initializes arrays to store the 
results and to account for overlap.
        r
esult = np.zeros((1, 2, mixture.shape[-1]), d
type=np.float32)
        divider = np.zeros((
1, 2, mixture.shape[-1]), dtype=np.float32)


        # Initializes counters for processing
 chunks.
        total = 0
        total_chun
ks = (mixture.shape[-1] + step - 1) // step
 
       self.logger.debug(f"Total chunks to pr
ocess: {total_chunks}")

        # Processes 
each chunk of the mixture.
        for i in t
qdm(range(0, mixture.shape[-1], step)):
     
       total += 1
            start = i
     
       end = min(i + chunk_size, mixture.shap
e[-1])
            self.logger.debug(f"Proces
sing chunk {total}/{total_chunks}: Start {sta
rt}, End {end}")

            # Handles windo
wing for overlapping chunks.
            chun
k_size_actual = end - start
            windo
w = None
            if overlap != 0:
       
         window = np.hanning(chunk_size_actua
l)
                window = np.tile(window[No
ne, None, :], (1, 2, 1))
                self
.logger.debug("Window applied to the chunk.")


            # Zero-pad the chunk to prepare
 it for processing.
            mix_part_ = m
ixture[:, start:end]
            if end != i 
+ chunk_size:
                pad_size = (i +
 chunk_size) - end
                mix_part_ 
= np.concatenate((mix_part_, np.zeros((2, pad
_size), dtype="float32")), axis=-1)

        
    # Converts the chunk to a tensor for proc
essing.
            mix_part = torch.tensor([
mix_part_], dtype=torch.float32).to(self.torc
h_device)
            # Splits the chunk into
 smaller batches if necessary.
            mi
x_waves = mix_part.split(self.batch_size)
   
         total_batches = len(mix_waves)
     
       self.logger.debug(f"Mix part split int
o batches. Number of batches: {total_batches}
")

            with torch.no_grad():
       
         # Processes each batch in the chunk.

                batches_processed = 0
      
          for mix_wave in mix_waves:
        
            batches_processed += 1
          
          self.logger.debug(f"Processing mix_
wave batch {batches_processed}/{total_batches
}")

                    # Runs the model to 
separate the sources.
                    tar
_waves = self.run_model(mix_wave, is_match_mi
x=is_match_mix)

                    # Applie
s windowing if needed and accumulates the res
ults.
                    if window is not No
ne:
                        tar_waves[..., :c
hunk_size_actual] *= window
                 
       divider[..., start:end] += window
    
                else:
                       
 divider[..., start:end] += 1

              
      result[..., start:end] += tar_waves[...
, : end - start]

        # Normalizes the re
sults by the divider to account for overlap.

        self.logger.debug("Normalizing result
 by dividing result by divider.")
        tar
_waves = result / divider
        tar_waves_.
append(tar_waves)

        # Reshapes the res
ults to match the original dimensions.
      
  tar_waves_ = np.vstack(tar_waves_)[:, :, se
lf.trim : -self.trim]
        tar_waves = np.
concatenate(tar_waves_, axis=-1)[:, : mix.sha
pe[-1]]

        # Extracts the source from t
he results.
        source = tar_waves[:, 0:N
one]
        self.logger.debug(f"Concatenated
 tar_waves. Shape: {tar_waves.shape}")

     
   # TODO: In UVR, pitch changing happens her
e. Consider implementing this as a feature.


        # TODO: In UVR, VR denoise model gets
 applied here. Consider implementing this as 
a feature.

        self.logger.debug("Demixi
ng process completed.")
        return source


    def run_model(self, mix, is_match_mix=F
alse):
        """
        Processes the inpu
t mix through the model to separate the sourc
es.
        Applies STFT, handles spectrum mo
difications, and runs the model for source se
paration.
        """
        # Applying the 
STFT to the mix. The mix is moved to the spec
ified device (e.g., GPU) before processing.
 
       # self.logger.debug(f"Running STFT on 
the mix. Mix shape before STFT: {mix.shape}")

        spek = self.stft(mix.to(self.torch_d
evice))
        self.logger.debug(f"STFT appl
ied on mix. Spectrum shape: {spek.shape}")

 
       # Zeroing out the first 3 bins of the 
spectrum. This is often done to reduce low-fr
equency noise.
        spek[:, :, :3, :] *= 0

        # self.logger.debug("First 3 bins of
 the spectrum zeroed out.")

        # Handli
ng the case where the mix needs to be matched
 (is_match_mix = True)
        if is_match_mi
x:
            # self.logger.debug("Match mix
 mode is enabled. Converting spectrum to NumP
y array.")
            spec_pred = spek.cpu()
.numpy()
            self.logger.debug("is_ma
tch_mix: spectrum prediction obtained directl
y from STFT output.")
        else:
         
   # If denoising is enabled, the model is ru
n on both the negative and positive spectrums
.
            if self.enable_denoise:
       
         # Assuming spek is a tensor and self
.model_run can process it directly
          
      spec_pred_neg = self.model_run(-spek)  
# Ensure this line correctly negates spek and
 runs the model
                spec_pred_pos
 = self.model_run(spek)
                # Ens
ure both spec_pred_neg and spec_pred_pos are 
tensors before applying operations
          
      spec_pred = (spec_pred_neg * -0.5) + (s
pec_pred_pos * 0.5)  # [invalid-unary-operand
-type]
                self.logger.debug("Mod
el run on both negative and positive spectrum
s for denoising.")
            else:
        
        spec_pred = self.model_run(spek)
    
            self.logger.debug("Model run on t
he spectrum without denoising.")

        # A
pplying the inverse STFT to convert the spect
rum back to the time domain.
        result =
 self.stft.inverse(torch.tensor(spec_pred).to
(self.torch_device)).cpu().detach().numpy()
 
       self.logger.debug(f"Inverse STFT appli
ed. Returning result with shape: {result.shap
e}")

        return result


