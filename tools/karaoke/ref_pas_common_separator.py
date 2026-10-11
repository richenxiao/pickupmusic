""" This file contains the CommonSeparator cl
ass, common to all architecture-specific Sepa
rator classes. """

from logging import Logge
r
import os
import re
import gc
import numpy 
as np
import librosa
import torch
from pydub 
import AudioSegment
import soundfile as sf
fr
om audio_separator.separator.audio_io import 
atomic_output_path, validate_audio_source
fro
m audio_separator.separator.exceptions import
 AudioExportError, InvalidAudioDataError
from
 audio_separator.separator.uvr_lib_v5 import 
spec_utils
from audio_separator.separator.exe
cution_policy import FP32, resolve_execution_
policy


class CommonSeparator:
    """
    T
his class contains the common methods and att
ributes common to all architecture-specific S
eparator classes.
    """

    ALL_STEMS = "A
ll Stems"
    VOCAL_STEM = "Vocals"
    INST_
STEM = "Instrumental"
    OTHER_STEM = "Other
"
    BASS_STEM = "Bass"
    DRUM_STEM = "Dru
ms"
    GUITAR_STEM = "Guitar"
    PIANO_STEM
 = "Piano"
    SYNTH_STEM = "Synthesizer"
   
 STRINGS_STEM = "Strings"
    WOODWINDS_STEM 
= "Woodwinds"
    BRASS_STEM = "Brass"
    WI
ND_INST_STEM = "Wind Inst"
    NO_OTHER_STEM 
= "No Other"
    NO_BASS_STEM = "No Bass"
   
 NO_DRUM_STEM = "No Drums"
    NO_GUITAR_STEM
 = "No Guitar"
    NO_PIANO_STEM = "No Piano"

    NO_SYNTH_STEM = "No Synthesizer"
    NO_
STRINGS_STEM = "No Strings"
    NO_WOODWINDS_
STEM = "No Woodwinds"
    NO_WIND_INST_STEM =
 "No Wind Inst"
    NO_BRASS_STEM = "No Brass
"
    PRIMARY_STEM = "Primary Stem"
    SECON
DARY_STEM = "Secondary Stem"
    LEAD_VOCAL_S
TEM = "lead_only"
    BV_VOCAL_STEM = "backin
g_only"
    LEAD_VOCAL_STEM_I = "with_lead_vo
cals"
    BV_VOCAL_STEM_I = "with_backing_voc
als"
    LEAD_VOCAL_STEM_LABEL = "Lead Vocals
"
    BV_VOCAL_STEM_LABEL = "Backing Vocals"

    NO_STEM = "No "

    STEM_PAIR_MAPPER = {
VOCAL_STEM: INST_STEM, INST_STEM: VOCAL_STEM,
 LEAD_VOCAL_STEM: BV_VOCAL_STEM, BV_VOCAL_STE
M: LEAD_VOCAL_STEM, PRIMARY_STEM: SECONDARY_S
TEM}

    NON_ACCOM_STEMS = (VOCAL_STEM, OTHE
R_STEM, BASS_STEM, DRUM_STEM, GUITAR_STEM, PI
ANO_STEM, SYNTH_STEM, STRINGS_STEM, WOODWINDS
_STEM, BRASS_STEM, WIND_INST_STEM)

    def _
_init__(self, config):

        self.logger: 
Logger = config.get("logger")
        self.lo
g_level: int = config.get("log_level")

     
   # Inferencing device / acceleration config

        self.torch_device = config.get("torc
h_device")
        self.requested_torch_devic
e = self.torch_device
        self.torch_devi
ce_cpu = config.get("torch_device_cpu")
     
   self.torch_device_mps = config.get("torch_
device_mps")
        self.onnx_execution_prov
ider = config.get("onnx_execution_provider")


        # Model data
        self.model_name
 = config.get("model_name")
        self.mode
l_path = config.get("model_path")
        sel
f.model_data = config.get("model_data")

    
    # Output directory and format
        sel
f.output_dir = config.get("output_dir")
     
   self.output_format = config.get("output_fo
rmat")
        self.output_bitrate = config.g
et("output_bitrate")

        # Functional op
tions which are applicable to all architectur
es and the user may tweak to affect the outpu
t
        self.normalization_threshold = conf
ig.get("normalization_threshold")
        sel
f.amplification_threshold = config.get("ampli
fication_threshold")
        self.enable_deno
ise = config.get("enable_denoise")
        se
lf.output_single_stem = config.get("output_si
ngle_stem")
        self.invert_using_spec = 
config.get("invert_using_spec")
        self.
sample_rate = config.get("sample_rate")
     
   self.use_soundfile = config.get("use_sound
file")
        self.use_autocast = config.get
("use_autocast", False)
        self.use_nati
ve_fp16 = config.get("use_native_fp16", False
)
        self.use_torch_compile = config.get
("use_torch_compile", False)
        self.use
s_pytorch_inference = True
        self.is_na
tive_fp16 = False
        self.effective_prec
ision = FP32
        self.effective_torch_com
pile = False
        self._should_torch_compi
le = False
        self._execution_policy_res
olved = False
        
        # Roformer-spe
cific loading support
        self.roformer_l
oader = None
        self.is_roformer_model =
 self._detect_roformer_model()
        if sel
f.is_roformer_model:
            self._initia
lize_roformer_loader()

        # Model speci
fic properties

        # Check if model_data
 has a "training" key with "instruments" list

        self.primary_stem_name = None
      
  self.secondary_stem_name = None
        
  
      # Audio bit depth tracking for preservi
ng input quality
        self.input_bit_depth
 = None
        self.input_subtype = None

  
      if "training" in self.model_data and "i
nstruments" in self.model_data["training"]:
 
           instruments = self.model_data["tra
ining"]["instruments"]
            if instrum
ents:
                target_instrument = sel
f.model_data["training"].get("target_instrume
nt")

                # When target_instrumen
t is set and doesn't match instruments[0],
  
              # the model's prediction would 
be labeled with the wrong stem name.
        
        # Swap so primary_stem_name always ma
tches the model's actual target output.
     
           if target_instrument and len(instr
uments) >= 2 and instruments[0] != target_ins
trument and instruments[1] == target_instrume
nt:
                    self.logger.debug(f"S
wapping stem names: target_instrument '{targe
t_instrument}' doesn't match instruments[0] '
{instruments[0]}'")
                    self.
primary_stem_name = instruments[1]
          
          self.secondary_stem_name = instrume
nts[0]
                else:
                
    self.primary_stem_name = instruments[0]
 
                   self.secondary_stem_name =
 instruments[1] if len(instruments) > 1 else 
self.secondary_stem(self.primary_stem_name)


        if self.primary_stem_name is None:
  
          self.primary_stem_name = self.model
_data.get("primary_stem", "Vocals")
         
   self.secondary_stem_name = self.secondary_
stem(self.primary_stem_name)

        self.is
_karaoke = self.model_data.get("is_karaoke", 
False)
        self.is_bv_model = self.model_
data.get("is_bv_model", False)
        self.b
v_model_rebalance = self.model_data.get("is_b
v_model_rebalanced", 0)

        self.logger.
debug(f"Common params: model_name={self.model
_name}, model_path={self.model_path}")
      
  self.logger.debug(f"Common params: output_d
ir={self.output_dir}, output_format={self.out
put_format}")
        self.logger.debug(f"Com
mon params: normalization_threshold={self.nor
malization_threshold}, amplification_threshol
d={self.amplification_threshold}")
        se
lf.logger.debug(f"Common params: enable_denoi
se={self.enable_denoise}, output_single_stem=
{self.output_single_stem}")
        self.logg
er.debug(f"Common params: invert_using_spec={
self.invert_using_spec}, sample_rate={self.sa
mple_rate}")

        self.logger.debug(f"Com
mon params: primary_stem_name={self.primary_s
tem_name}, secondary_stem_name={self.secondar
y_stem_name}")
        self.logger.debug(f"Co
mmon params: is_karaoke={self.is_karaoke}, is
_bv_model={self.is_bv_model}, bv_model_rebala
nce={self.bv_model_rebalance}")

        # Fi
le-specific variables which need to be cleare
d between processing different audio inputs
 
       self.audio_file_path = None
        se
lf.audio_file_base = None

        self.prima
ry_source = None
        self.secondary_sourc
e = None

        self.primary_stem_output_pa
th = None
        self.secondary_stem_output_
path = None

        self.cached_sources_map 
= {}

    def resolve_execution_policy(self, 
model_family):
        """Resolve the request
ed precision and compilation settings for thi
s model."""
        policy = resolve_executio
n_policy(
            device=self.torch_devic
e,
            requested_device=self.requeste
d_torch_device,
            model_family=mode
l_family,
            use_autocast=self.use_a
utocast,
            use_native_fp16=self.use
_native_fp16,
            use_torch_compile=s
elf.use_torch_compile,
            uses_pytor
ch_inference=getattr(self, "uses_pytorch_infe
rence", True),
            logger=self.logger
,
        )
        self.effective_precision 
= policy.precision
        self.effective_tor
ch_compile = False
        self._should_torch
_compile = policy.use_torch_compile
        s
elf._execution_policy_resolved = True
       
 return policy

    def secondary_stem(self, 
primary_stem: str):
        """Determines sec
ondary stem name based on the primary stem na
me."""
        primary_stem = primary_stem if
 primary_stem else self.NO_STEM

        if p
rimary_stem in self.STEM_PAIR_MAPPER:
       
     secondary_stem = self.STEM_PAIR_MAPPER[p
rimary_stem]
        else:
            second
ary_stem = primary_stem.replace(self.NO_STEM,
 "") if self.NO_STEM in primary_stem else f"{
self.NO_STEM}{primary_stem}"

        return 
secondary_stem

    def separate(self, audio_
file_path):
        """
        Placeholder m
ethod for separating audio sources. Should be
 overridden by subclasses.
        """
      
  raise NotImplementedError("This method shou
ld be overridden by subclasses.")

    def fi
nal_process(self, stem_path, source, stem_nam
e):
        """
        Finalizes the process
ing of a stem by writing the audio to a file 
and returning the processed source.
        "
""
        self.logger.debug(f"Finalizing {st
em_name} stem processing and writing audio...
")
        self.write_audio(stem_path, source
)

        return {stem_name: source}

    de
f cached_sources_clear(self):
        """
   
     Clears the cache dictionaries for VR, MD
X, and Demucs models.

        This function 
is essential for ensuring that the cache does
 not hold outdated or irrelevant data
       
 between different processing sessions or whe
n a new batch of audio files is processed.
  
      It helps in managing memory efficiently
 and prevents potential errors due to stale d
ata.
        """
        self.cached_sources_
map = {}

    def cached_source_callback(self
, model_architecture, model_name=None):
     
   """
        Retrieves the model and source
s from the cache based on the processing meth
od and model name.

        Args:
           
 model_architecture: The architecture type (V
R, MDX, or Demucs) being used for processing.

            model_name: The specific model n
ame within the architecture type, if applicab
le.

        Returns:
            A tuple con
taining the model and its sources if found in
 the cache; otherwise, None.

        This fu
nction is crucial for optimizing performance 
by avoiding redundant processing.
        If 
the requested model and its sources are alrea
dy in the cache, they can be reused directly,

        saving time and computational resour
ces.
        """
        model, sources = Non
e, None

        mapper = self.cached_sources
_map[model_architecture]

        for key, va
lue in mapper.items():
            if model_n
ame in key:
                model = key
     
           sources = value

        return mo
del, sources

    def cached_model_source_hol
der(self, model_architecture, sources, model_
name=None):
        """
        Update the di
ctionary for the given model_architecture wit
h the new model name and its sources.
       
 Use the model_architecture as a key to acces
s the corresponding cache source mapper dicti
onary.
        """
        self.cached_source
s_map[model_architecture] = {**self.cached_so
urces_map.get(model_architecture, {}), **{mod
el_name: sources}}

    def prepare_mix(self,
 mix):
        """
        Prepares the mix f
or processing. This includes loading the audi
o from a file if necessary,
        ensuring 
the mix is in the correct format, and convert
ing mono to stereo if needed.
        """
   
     # Store the original path or the mix its
elf for later checks
        audio_path = mix


        # Check if the input is a file path
 (string) and needs to be loaded
        if n
ot isinstance(mix, np.ndarray):
            s
elf.logger.debug(f"Loading audio from file: {
mix}")
            
            # Get audio f
ile info to capture bit depth before loading

            try:
                audio_info =
 sf.info(mix)
                self.input_subt
ype = audio_info.subtype
                self
.logger.info(f"Input audio subtype: {self.inp
ut_subtype}")
                
              
  # Map subtype to bit depth
                
if 'PCM_16' in self.input_subtype or self.inp
ut_subtype == 'PCM_S8':
                    s
elf.input_bit_depth = 16
                elif
 'PCM_24' in self.input_subtype:
            
        self.input_bit_depth = 24
           
     elif 'PCM_32' in self.input_subtype or '
FLOAT' in self.input_subtype or 'DOUBLE' in s
elf.input_subtype:
                    self.i
nput_bit_depth = 32
                else:
   
                 # Default to 16-bit for unkn
own formats
                    self.input_bi
t_depth = 16
                    self.logger.
warning(f"Unknown audio subtype {self.input_s
ubtype}, defaulting to 16-bit output")
      
          
                self.logger.info(f
"Detected input bit depth: {self.input_bit_de
pth}-bit")
            except Exception as e:

                self.logger.warning(f"Could 
not read audio file info, defaulting to 16-bi
t output: {e}")
                self.input_bi
t_depth = 16
                self.input_subty
pe = 'PCM_16'
            
            mix, s
r = librosa.load(mix, mono=False, sr=self.sam
ple_rate)
            self.logger.debug(f"Aud
io loaded. Sample rate: {sr}, Audio shape: {m
ix.shape}")
        else:
            # Trans
pose the mix if it's already an ndarray (expe
cted shape: [channels, samples])
            
self.logger.debug("Transposing the provided m
ix array.")
            # Default to 16-bit i
f numpy array provided directly
            i
f self.input_bit_depth is None:
             
   self.input_bit_depth = 16
                
self.input_subtype = 'PCM_16'
            mix
 = mix.T
            self.logger.debug(f"Tran
sposed mix shape: {mix.shape}")

        sour
ce_description = "Audio array" if isinstance(
audio_path, np.ndarray) else f"Audio file {au
dio_path}"
        if mix.size == 0:
        
    error_msg = f"{source_description} contai
ns no audio frames"
            self.logger.e
rror(error_msg)
            raise InvalidAudi
oDataError(error_msg)
        elif not np.isf
inite(mix).all():
            error_msg = f"{
source_description} contains non-finite sampl
es"
            self.logger.error(error_msg)

            raise InvalidAudioDataError(error
_msg)
        else:
            self.logger.d
ebug("Audio input is valid and contains audio
 frames.")

        # Ensure the mix is in st
ereo format
        if mix.ndim == 1:
       
     self.logger.debug("Mix is mono. Converti
ng to stereo.")
            mix = np.asfortra
narray([mix, mix])
            self.logger.de
bug("Converted to stereo mix.")

        # Fi
nal log indicating successful preparation of 
the mix
        self.logger.debug("Mix prepar
ation completed.")
        return mix

    de
f write_audio(self, stem_path: str, stem_sour
ce):
        """
        Writes the separated
 audio source to a file using pydub or soundf
ile
        Pydub supports a much wider range
 of audio formats and produces better encoded
 lossy files for some formats.
        Soundf
ile is used for very large files (longer than
 1 hour), as pydub has memory issues with lar
ge files:
        https://github.com/jiaaro/p
ydub/issues/135
        """
        # Get the
 duration of the input audio file
        dur
ation_seconds = librosa.get_duration(path=sel
f.audio_file_path)
        duration_hours = d
uration_seconds / 3600
        self.logger.in
fo(f"Audio duration is {duration_hours:.2f} h
ours ({duration_seconds:.2f} seconds).")

   
     if self.use_soundfile:
            self.
logger.warning(f"Using soundfile for writing.
")
            self.write_audio_soundfile(ste
m_path, stem_source)
        else:
          
  self.logger.info(f"Using pydub for writing.
")
            self.write_audio_pydub(stem_pa
th, stem_source)

    def write_audio_pydub(s
elf, stem_path: str, stem_source):
        ""
"
        Writes the separated audio source t
o a file using pydub (ffmpeg)
        """
   
     self.logger.debug(f"Entering write_audio
_pydub with stem_path: {stem_path}")

       
 stem_source = validate_audio_source(stem_sou
rce)
        stem_source = spec_utils.normali
ze(wave=stem_source, max_peak=self.normalizat
ion_threshold, min_peak=self.amplification_th
reshold)

        # If output_dir is specifie
d, create it and join it with stem_path
     
   if self.output_dir:
            stem_path 
= os.path.join(self.output_dir, stem_path)
  
          try:
                os.makedirs(se
lf.output_dir, exist_ok=True)
            exc
ept Exception as e:
                raise Aud
ioExportError(f"Failed to prepare output dire
ctory for {stem_path}: {e}", path=stem_path, 
backend="pydub") from e

        self.logger.
debug(f"Audio data shape before processing: {
stem_source.shape}")
        self.logger.debu
g(f"Data type before conversion: {stem_source
.dtype}")

        # Determine bit depth for 
output (use input bit depth if available, oth
erwise default to 16)
        output_bit_dept
h = self.input_bit_depth if self.input_bit_de
pth is not None else 16
        self.logger.i
nfo(f"Writing output with {output_bit_depth}-
bit depth")

        # For pydub, we always c
onvert to int16 for the AudioSegment creation

        # Then let ffmpeg handle the convers
ion to the target bit depth during export
   
     if stem_source.dtype != np.int16:
      
      stem_source = (stem_source * 32767).ast
ype(np.int16)
            self.logger.debug("
Converted stem_source to int16 for pydub proc
essing.")

        channels = 1 if stem_sourc
e.ndim == 1 else stem_source.shape[1]
       
 stem_source_interleaved = np.ascontiguousarr
ay(stem_source).reshape(-1)

        self.log
ger.debug(f"Interleaved audio data shape: {st
em_source_interleaved.shape}")

        # Cre
ate a pydub AudioSegment (always from 16-bit 
data)
        try:
            audio_segment 
= AudioSegment(stem_source_interleaved.tobyte
s(), frame_rate=self.sample_rate, sample_widt
h=2, channels=channels)
            self.logg
er.debug("Created AudioSegment successfully."
)
        except Exception as e:
            
raise AudioExportError(f"Failed to create aud
io for {stem_path} with pydub: {e}", path=ste
m_path, backend="pydub") from e

        # De
termine file format based on the file extensi
on
        file_format = stem_path.lower().sp
lit(".")[-1]

        # For m4a files, specif
y mp4 as the container format as the extensio
n doesn't match the format name
        if fi
le_format == "m4a":
            file_format =
 "mp4"
        elif file_format == "mka":
   
         file_format = "matroska"

        # 
Set the bitrate to 320k for mp3 files if outp
ut_bitrate is not specified
        bitrate =
 "320k" if file_format == "mp3" and self.outp
ut_bitrate is None else self.output_bitrate


        target_path = stem_path

        # Ex
port using the determined format
        with
 atomic_output_path(target_path, "pydub") as 
temp_path:
            # Pass codec parameter
s to ffmpeg to enforce bit depth for lossless
 formats
            export_params = {"format
": file_format}
            
            if b
itrate:
                export_params["bitrat
e"] = bitrate
            
            # For 
lossless formats (WAV/FLAC), specify the code
c parameters to enforce bit depth
           
 if file_format in ["wav", "flac"]:
         
       if output_bit_depth == 16:
           
         export_params["parameters"] = ["-sam
ple_fmt", "s16"]
                elif output_
bit_depth == 24:
                    export_p
arams["parameters"] = ["-sample_fmt", "s32"]

                    # For 24-bit, we also nee
d to specify the bit depth explicitly
       
             if file_format == "wav":
       
                 export_params["codec"] = "pc
m_s24le"
                    elif file_format
 == "flac":
                        # FLAC su
pports 24-bit natively, no special handling n
eeded
                        pass
          
      elif output_bit_depth == 32:
          
          export_params["parameters"] = ["-sa
mple_fmt", "s32"]
                    if file
_format == "wav":
                        exp
ort_params["codec"] = "pcm_s32le"
           
 
            export_handle = audio_segment.e
xport(temp_path, **export_params)
           
 close_export = getattr(export_handle, "close
", None)
            if callable(close_export
):
                close_export()
        sel
f.logger.debug(f"Exported audio file successf
ully to {target_path} with {output_bit_depth}
-bit depth")

    def write_audio_soundfile(s
elf, stem_path: str, stem_source):
        ""
"
        Writes the separated audio source t
o a file using soundfile library.
        """

        self.logger.debug(f"Entering write_a
udio_soundfile with stem_path: {stem_path}")


        stem_source = validate_audio_source(
stem_source)
        stem_source = spec_utils
.normalize(wave=stem_source, max_peak=self.no
rmalization_threshold, min_peak=self.amplific
ation_threshold)

        # If output_dir is 
specified, create it and join it with stem_pa
th
        if self.output_dir:
            st
em_path = os.path.join(self.output_dir, stem_
path)
            try:
                os.mak
edirs(self.output_dir, exist_ok=True)
       
     except Exception as e:
                r
aise AudioExportError(f"Failed to prepare out
put directory for {stem_path}: {e}", path=ste
m_path, backend="soundfile") from e

        
# Determine the subtype based on the input au
dio's bit depth
        output_subtype = None

        if self.input_subtype:
            o
utput_subtype = self.input_subtype
          
  self.logger.info(f"Using input subtype for 
output: {output_subtype}")
        elif self.
input_bit_depth:
            # Map bit depth 
to subtype
            if self.input_bit_dept
h == 16:
                output_subtype = 'PC
M_16'
            elif self.input_bit_depth =
= 24:
                output_subtype = 'PCM_2
4'
            elif self.input_bit_depth == 3
2:
                output_subtype = 'PCM_32'

            else:
                output_subt
ype = 'PCM_16'  # Default fallback
          
  self.logger.info(f"Using output subtype bas
ed on bit depth: {output_subtype}")
        e
lse:
            # Default to PCM_16 if no bi
t depth info available
            output_sub
type = 'PCM_16'
            self.logger.warni
ng("No bit depth info available, defaulting t
o PCM_16")

        # Correctly interleave st
ereo channels if needed
        if stem_sourc
e.ndim == 2 and stem_source.shape[1] == 2:
  
          # If the audio is already interleav
ed, ensure it's in the correct order
        
    # Check if the array is Fortran contiguou
s (column-major)
            if stem_source.f
lags["F_CONTIGUOUS"]:
                # Conve
rt to C contiguous (row-major)
              
  stem_source = np.ascontiguousarray(stem_sou
rce)
            # No need to manually interl
eave for soundfile - it handles multi-channel
 properly
            # Just ensure we don't 
have the wrong shape

        self.logger.deb
ug(f"Audio data shape for soundfile: {stem_so
urce.shape}")

        """
        Write audi
o using soundfile (for formats other than M4A
).
        """
        target_path = stem_pat
h
        with atomic_output_path(target_path
, "soundfile") as temp_path:
            # Sp
ecify the subtype to match input bit depth
  
          sf.write(temp_path, stem_source, se
lf.sample_rate, subtype=output_subtype)
     
   self.logger.debug(f"Exported audio file su
ccessfully to {target_path} with subtype {out
put_subtype}")

    def clear_gpu_cache(self)
:
        """
        This method clears the 
GPU cache to free up memory.
        """
    
    self.logger.debug("Running garbage collec
tion...")
        gc.collect()
        if sel
f.torch_device == torch.device("mps"):
      
      self.logger.debug("Clearing MPS cache..
.")
            torch.mps.empty_cache()
     
   if self.torch_device == torch.device("cuda
"):
            self.logger.debug("Clearing C
UDA cache...")
            torch.cuda.empty_c
ache()

    def clear_file_specific_paths(sel
f):
        """
        Clears the file-speci
fic variables which need to be cleared betwee
n processing different audio inputs.
        
"""
        self.logger.info("Clearing input 
audio file paths, sources and stems...")

   
     self.audio_file_path = None
        self
.audio_file_base = None

        self.primary
_source = None
        self.secondary_source 
= None

        self.primary_stem_output_path
 = None
        self.secondary_stem_output_pa
th = None

    def sanitize_filename(self, fi
lename):
        """
        Cleans the filen
ame by replacing invalid characters with unde
rscores.
        """
        sanitized = re.s
ub(r'[<>:"/\\|?*]', '_', filename)
        sa
nitized = re.sub(r'_+', '_', sanitized)
     
   sanitized = sanitized.strip('_. ')
       
 return sanitized

    def get_stem_output_pa
th(self, stem_name, custom_output_names):
   
     """
        Gets the output path for a s
tem based on the stem name and custom output 
names.
        """
        # Convert custom_o
utput_names keys to lowercase for case-insens
itive comparison
        if custom_output_nam
es:
            custom_output_names_lower = {
k.lower(): v for k, v in custom_output_names.
items()}
            stem_name_lower = stem_n
ame.lower()
            if stem_name_lower in
 custom_output_names_lower:
                s
anitized_custom_name = self.sanitize_filename
(custom_output_names_lower[stem_name_lower])

                return os.path.join(f"{saniti
zed_custom_name}.{self.output_format.lower()}
")

        sanitized_audio_base = self.sanit
ize_filename(self.audio_file_base)
        sa
nitized_stem_name = self.sanitize_filename(st
em_name)
        sanitized_model_name = self.
sanitize_filename(self.model_name) 

        
filename = f"{sanitized_audio_base}_({sanitiz
ed_stem_name})_{sanitized_model_name}.{self.o
utput_format.lower()}"
        return os.path
.join(filename)
    
    def _detect_roformer
_model(self):
        """
        Detect if t
he current model is a Roformer model.
       
 
        Returns:
            bool: True if 
this is a Roformer model, False otherwise
   
     """
        if not self.model_data:
    
        return False
            
        # C
heck for explicit Roformer flag
        if se
lf.model_data.get("is_roformer", False):
    
        return True

        # Prefer archite
cture-defining configuration over renameable 
paths.
        from .roformer.configuration_n
ormalizer import ConfigurationNormalizer

   
     if ConfigurationNormalizer().detect_mode
l_type(self.model_data) is not None:
        
    return True
            
        # Keep f
ilename-based compatibility without letting p
arent directories
        # influence archite
cture routing.
        if self.model_path and
 "roformer" in os.path.basename(self.model_pa
th).lower():
            return True
        
    
        # Check model name for Roformer 
indicators
        if self.model_name and "ro
former" in self.model_name.lower():
         
   return True
            
        return Fa
lse
    
    def _initialize_roformer_loader(
self):
        """
        Initialize the Rof
ormer loader for this model.
        """
    
    try:
            from .roformer.roformer_
loader import RoformerLoader
            self
.roformer_loader = RoformerLoader()
         
   self.logger.debug("Initialized Roformer lo
ader for CommonSeparator")
        except Imp
ortError as e:
            self.logger.warnin
g(f"Could not import RoformerLoader: {e}")
  
          self.roformer_loader = None
    
  
  def get_roformer_loading_stats(self):
     
   """
        Get Roformer loading statistic
s if available.
        
        Returns:
   
         dict: Loading statistics or empty di
ct if not available
        """
        if se
lf.roformer_loader:
            return self.r
oformer_loader.get_loading_stats()
        re
turn {}
    
    def validate_roformer_config
(self, config, model_type):
        """
     
   Validate Roformer configuration if loader 
is available.
        
        Args:
        
    config: Configuration dictionary to valid
ate
            model_type: Type of model to 
validate for
            
        Returns:
  
          bool: True if valid or validation n
ot available, False if invalid
        """
  
      if self.roformer_loader:
            re
turn self.roformer_loader.validate_configurat
ion(config, model_type)
        return True  
# Assume valid if no loader available


