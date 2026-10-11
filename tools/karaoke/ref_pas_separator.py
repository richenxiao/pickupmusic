""" This file contains the Separator class, t
o facilitate the separation of stems from aud
io. """

from importlib import metadata, reso
urces
import os
import sys
import platform
im
port subprocess
import time
import logging
im
port warnings
import importlib
import io
impo
rt re
import librosa
import numpy as np
from 
typing import Optional

import hashlib
import
 json
import yaml
import requests
import torc
h
import torch.amp.autocast_mode as autocast_
mode
import onnxruntime as ort
from tqdm impo
rt tqdm
from audio_separator.separator.audio_
io import atomic_output_path, validate_audio_
source
from audio_separator.separator.ensembl
er import Ensembler
from audio_separator.sepa
rator.exceptions import AudioExportError, Bat
chSeparationError, InvalidAudioDataError
from
 audio_separator.separator.execution_policy i
mport AUTOCAST, FP32, NATIVE_FP16

# Mapping 
of common stem name variations to canonical n
ames for ensemble grouping.
STEM_NAME_MAP = {

    "vocals": "Vocals",
    "instrumental": 
"Instrumental",
    "inst": "Instrumental",
 
   "karaoke": "Instrumental",
    "other": "O
ther",
    "no_vocals": "Instrumental",
    "
drums": "Drums",
    "bass": "Bass",
    "gui
tar": "Guitar",
    "piano": "Piano",
    "sy
nthesizer": "Synthesizer",
    "strings": "St
rings",
    "woodwinds": "Woodwinds",
    "br
ass": "Brass",
    "wind inst": "Wind Inst",

    "lead vocals": "Lead Vocals",
    "backin
g vocals": "Backing Vocals",
    "primary ste
m": "Primary Stem",
    "secondary stem": "Se
condary Stem",
}

SUPPORTED_AUDIO_EXTENSIONS 
= (".wav", ".flac", ".mp3", ".ogg", ".opus", 
".m4a", ".aiff", ".ac3")


def _iter_director
y_audio_files(directory):
    for root, _dirs
, files in os.walk(directory):
        for fi
lename in files:
            if filename.lowe
r().endswith(SUPPORTED_AUDIO_EXTENSIONS):
   
             yield os.path.join(root, filenam
e)


class Separator:
    """
    The Separat
or class is designed to facilitate the separa
tion of audio sources from a given audio file
.
    It supports various separation architec
tures and models, including MDX, VR, and Demu
cs. The class provides
    functionalities to
 configure separation parameters, load models
, and perform audio source separation.
    It
 also handles logging, normalization, and out
put formatting of the separated audio stems.


    The actual separation task is handled by
 one of the architecture-specific classes in 
the `architectures` module;
    this class is
 responsible for initialising logging, config
uring hardware acceleration, loading the mode
l,
    initiating the separation process and 
passing outputs back to the caller.

    Comm
on Attributes:
        log_level (int): The l
ogging level.
        log_formatter (logging.
Formatter): The logging formatter.
        mo
del_file_dir (str): The directory where model
 files are stored.
        output_dir (str): 
The directory where output files will be save
d.
        output_format (str): The format of
 the output audio file.
        output_bitrat
e (str): The bitrate of the output audio file
.
        amplification_threshold (float): Th
e threshold for audio amplification.
        
normalization_threshold (float): The threshol
d for audio normalization.
        output_sin
gle_stem (str): Option to output a single ste
m.
        invert_using_spec (bool): Flag to 
invert using spectrogram.
        sample_rate
 (int): The sample rate of the audio.
       
 use_soundfile (bool): Use soundfile for audi
o writing, can solve OOM issues.
        use_
autocast (bool): Use PyTorch autocast when th
e loaded model and device support it.
       
 use_torch_compile (bool): Compile verified r
epeated model blocks when supported.
        
use_native_fp16 (bool): Convert a verified mo
del to native float16 inference when supporte
d.

    MDX Architecture Specific Attributes:

        hop_length (int): The hop length for
 STFT.
        segment_size (int): The segmen
t size for processing.
        overlap (float
): The overlap between segments.
        batc
h_size (int): The batch size for processing.

        enable_denoise (bool): Flag to enable
 or disable denoising.

    VR Architecture S
pecific Attributes & Defaults:
        batch_
size: 16
        window_size: 512
        agg
ression: 5
        enable_tta: False
        
enable_post_process: False
        post_proce
ss_threshold: 0.2
        high_end_process: F
alse

    Demucs Architecture Specific Attrib
utes & Defaults:
        segment_size: "Defau
lt"
        shifts: 2
        overlap: 0.25
 
       segments_enabled: True

    MDXC Archi
tecture Specific Attributes & Defaults:
     
   segment_size: 256
        override_model_s
egment_size: False
        batch_size: None (
uses inference.batch_size from the model YAML
, falling back to 1)
        overlap: None (u
ses inference.num_overlap from the model YAML
, falling back to 8)
        pitch_shift: 0
 
   """

    def __init__(
        self,
     
   log_level=logging.INFO,
        log_format
ter=None,
        model_file_dir="/tmp/audio-
separator-models/",
        output_dir=None,

        output_format="WAV",
        output_b
itrate=None,
        normalization_threshold=
0.9,
        amplification_threshold=0.0,
   
     output_single_stem=None,
        invert_
using_spec=False,
        sample_rate=44100,

        use_soundfile=False,
        use_auto
cast=False,
        use_directml=False,
     
   chunk_duration=None,
        mdx_params={"
hop_length": 1024, "segment_size": 256, "over
lap": 0.25, "batch_size": 1, "enable_denoise"
: False},
        vr_params={"batch_size": 1,
 "window_size": 512, "aggression": 5, "enable
_tta": False, "enable_post_process": False, "
post_process_threshold": 0.2, "high_end_proce
ss": False},
        demucs_params={"segment_
size": "Default", "shifts": 2, "overlap": 0.2
5, "segments_enabled": True},
        mdxc_pa
rams={"segment_size": 256, "override_model_se
gment_size": False, "batch_size": None, "over
lap": None, "pitch_shift": 0},
        ensemb
le_algorithm=None,
        ensemble_weights=N
one,
        ensemble_preset=None,
        in
fo_only=False,
        use_torch_compile=Fals
e,
        use_native_fp16=False,
    ):
    
    """Initialize the separator."""
        i
f use_autocast and use_native_fp16:
         
   raise ValueError("use_autocast and use_nat
ive_fp16 are mutually exclusive precision mod
es.")

        self.logger = logging.getLogge
r(__name__)
        self.logger.setLevel(log_
level)
        self.log_level = log_level
   
     self.log_formatter = log_formatter

    
    self.log_handler = logging.StreamHandler(
)

        if self.log_formatter is None:
   
         self.log_formatter = logging.Formatt
er("%(asctime)s - %(levelname)s - %(module)s 
- %(message)s")

        self.log_handler.set
Formatter(self.log_formatter)

        if not
 self.logger.hasHandlers():
            self.
logger.addHandler(self.log_handler)

        
# Filter out noisy warnings from PyTorch for 
users who don't care about them
        if lo
g_level > logging.DEBUG:
            warnings
.filterwarnings("ignore")

        # Skip ini
tialization logs if info_only is True
       
 if not info_only:
            package_versio
n = self.get_package_distribution("audio-sepa
rator").version
            self.logger.info(
f"Separator version {package_version} instant
iating with output_dir: {output_dir}, output_
format: {output_format}")

        if output_
dir is None:
            output_dir = os.getc
wd()
            if not info_only:
          
      self.logger.info("Output directory not 
specified. Using current working directory.")


        self.output_dir = output_dir

     
   # Check for environment variable to overri
de model_file_dir
        env_model_dir = os.
environ.get("AUDIO_SEPARATOR_MODEL_DIR")
    
    if env_model_dir:
            self.model_
file_dir = env_model_dir
            self.log
ger.info(f"Using model directory from AUDIO_S
EPARATOR_MODEL_DIR env var: {self.model_file_
dir}")
            if not os.path.exists(self
.model_file_dir):
                raise FileN
otFoundError(f"The specified model directory 
does not exist: {self.model_file_dir}")
     
   else:
            self.logger.info(f"Using
 model directory from model_file_dir paramete
r: {model_file_dir}")
            self.model_
file_dir = model_file_dir

        # Create t
he model directory if it does not exist
     
   os.makedirs(self.model_file_dir, exist_ok=
True)
        os.makedirs(self.output_dir, ex
ist_ok=True)

        self.output_format = ou
tput_format
        self.output_bitrate = out
put_bitrate

        if self.output_format is
 None:
            self.output_format = "WAV"


        self.normalization_threshold = norm
alization_threshold
        if normalization_
threshold <= 0 or normalization_threshold > 1
:
            raise ValueError("The normaliza
tion_threshold must be greater than 0 and les
s than or equal to 1.")

        self.amplifi
cation_threshold = amplification_threshold
  
      if amplification_threshold < 0 or ampli
fication_threshold > 1:
            raise Val
ueError("The amplification_threshold must be 
greater than or equal to 0 and less than or e
qual to 1.")

        self.output_single_stem
 = output_single_stem
        if output_singl
e_stem is not None:
            self.logger.d
ebug(f"Single stem output requested, so only 
one output file ({output_single_stem}) will b
e written")

        self.invert_using_spec =
 invert_using_spec
        if self.invert_usi
ng_spec:
            self.logger.debug(f"Seco
ndary step will be inverted using spectogram 
rather than waveform. This may improve qualit
y but is slightly slower.")

        try:
   
         self.sample_rate = int(sample_rate)

            if self.sample_rate <= 0:
       
         raise ValueError(f"The sample rate s
etting is {self.sample_rate} but it must be a
 non-zero whole number.")
            if self
.sample_rate > 12800000:
                rais
e ValueError(f"The sample rate setting is {se
lf.sample_rate}. Enter something less ambitio
us.")
        except ValueError:
            
raise ValueError("The sample rate must be a n
on-zero whole number. Please provide a valid 
integer.")

        self.use_soundfile = use_
soundfile
        self.use_autocast = use_aut
ocast
        self.use_native_fp16 = use_nati
ve_fp16
        self.use_torch_compile = use_
torch_compile
        self.use_directml = use
_directml

        self.chunk_duration = chun
k_duration
        if chunk_duration is not N
one:
            if chunk_duration <= 0:
    
            raise ValueError("chunk_duration 
must be greater than 0")

        self.ensemb
le_algorithm = ensemble_algorithm
        sel
f.ensemble_weights = ensemble_weights
       
 self.ensemble_preset = ensemble_preset
     
   self._ensemble_preset_models = None

     
   # If an ensemble preset is specified, load
 it and apply defaults
        if ensemble_pr
eset is not None:
            preset_data = s
elf._load_ensemble_preset(ensemble_preset)
  
          self._ensemble_preset_models = pres
et_data["models"]
            # Preset values
 are defaults — explicit user args (non-Non
e) take priority
            if ensemble_algo
rithm is None:
                self.ensemble_
algorithm = preset_data["algorithm"]
        
    if ensemble_weights is None and preset_da
ta.get("weights") is not None:
              
  self.ensemble_weights = preset_data["weight
s"]

        # Apply default algorithm if sti
ll not set (no preset, no explicit arg)
     
   if self.ensemble_algorithm is None:
      
      self.ensemble_algorithm = "avg_wave"

 
       # These are parameters which users may
 want to configure so we expose them to the t
op-level Separator class,
        # even thou
gh they are specific to a single model archit
ecture
        self.arch_specific_params = {"
MDX": mdx_params, "VR": vr_params, "Demucs": 
demucs_params, "MDXC": mdxc_params}

        
self.torch_device = None
        self.torch_d
evice_cpu = None
        self.torch_device_mp
s = None

        self.onnx_execution_provide
r = None
        self.model_instance = None
 
       self.model_filename = None
        sel
f.model_filenames = []
        self._loaded_m
odel_filename = None
        self._loaded_mod
el_friendly_name = None
        self._loaded_
model_is_uvr_vip = False

        self.model_
is_uvr_vip = False
        self.model_friendl
y_name = None

        if not info_only:
    
        self.setup_accelerated_inferencing_de
vice()

    @property
    def effective_preci
sion(self):
        """Return the precision m
ode selected for the currently loaded model."
""
        if len(getattr(self, "model_filena
mes", ())) > 1:
            return FP32
     
   return getattr(self.model_instance, "effec
tive_precision", FP32)

    @property
    def
 effective_torch_compile(self):
        """Re
turn whether regional compilation is active f
or the loaded model."""
        if len(getatt
r(self, "model_filenames", ())) > 1:
        
    return False
        return bool(getattr(
self.model_instance, "effective_torch_compile
", False))

    VALID_ENSEMBLE_ALGORITHMS = [

        "avg_wave", "median_wave", "min_wave
", "max_wave",
        "avg_fft", "median_fft
", "min_fft", "max_fft",
        "uvr_max_spe
c", "uvr_min_spec", "ensemble_wav",
    ]

  
  def _load_ensemble_preset(self, preset_name
):
        """
        Load and validate an e
nsemble preset from ensemble_presets.json.

 
       Returns a dict with keys: name, descri
ption, models, algorithm, weights, contributo
r.
        Raises ValueError if the preset is
 not found or fails validation.
        """
 
       try:
            with resources.open_t
ext("audio_separator", "ensemble_presets.json
") as f:
                presets_data = json.
load(f)
        except FileNotFoundError:
   
         raise ValueError("Ensemble presets f
ile not found. The package may be corrupted o
r improperly installed.")

        presets = 
presets_data.get("presets", {})
        if pr
eset_name not in presets:
            availab
le = ", ".join(sorted(presets.keys()))
      
      raise ValueError(f"Unknown ensemble pre
set: '{preset_name}'. Available presets: {ava
ilable}")

        preset = presets[preset_na
me]

        # Validate models
        models
 = preset.get("models", [])
        if not is
instance(models, list) or len(models) < 2:
  
          raise ValueError(f"Ensemble preset 
'{preset_name}' must specify at least 2 model
s, got {len(models) if isinstance(models, lis
t) else 0}")

        # Validate algorithm
  
      algorithm = preset.get("algorithm", "av
g_wave")
        if algorithm not in self.VAL
ID_ENSEMBLE_ALGORITHMS:
            raise Val
ueError(f"Ensemble preset '{preset_name}' has
 unknown algorithm: '{algorithm}'")

        
# Validate weights
        weights = preset.g
et("weights")
        if weights is not None:

            if not isinstance(weights, list)
 or len(weights) != len(models):
            
    raise ValueError(f"Ensemble preset '{pres
et_name}' weights length ({len(weights) if is
instance(weights, list) else 'N/A'}) must mat
ch models count ({len(models)})")

        se
lf.logger.info(f"Loaded ensemble preset '{pre
set_name}': {preset.get('name', preset_name)}
 — {preset.get('description', '')}")
      
  return preset

    def list_ensemble_preset
s(self):
        """
        List all availab
le ensemble presets.

        Returns a dict 
mapping preset IDs to their full preset data.

        """
        try:
            with re
sources.open_text("audio_separator", "ensembl
e_presets.json") as f:
                preset
s_data = json.load(f)
        except FileNotF
oundError:
            return {}
        retu
rn presets_data.get("presets", {})

    def s
etup_accelerated_inferencing_device(self):
  
      """
        This method sets up the PyT
orch and/or ONNX Runtime inferencing device, 
using GPU hardware acceleration if available.

        """
        system_info = self.get_s
ystem_info()
        self.check_ffmpeg_instal
led()
        self.log_onnxruntime_packages()

        self.preload_onnxruntime_dependencie
s()
        self.setup_torch_device(system_in
fo)

    def preload_onnxruntime_dependencies
(self):
        """
        Preload ONNX Runt
ime shared library dependencies when supporte
d by the installed package.

        This hel
ps pip-installed CUDA/cuDNN runtime wheels be
come visible to ONNX Runtime before
        t
he first CUDAExecutionProvider session is cre
ated.
        """
        if not hasattr(ort,
 "preload_dlls"):
            self.logger.deb
ug("Installed ONNX Runtime does not provide p
reload_dlls(); skipping dependency preload.")

            return

        try:
           
 ort.preload_dlls()
            self.logger.d
ebug("Preloaded ONNX Runtime shared library d
ependencies.")
        except Exception as ex
c:
            self.logger.warning(f"Unable t
o preload ONNX Runtime shared library depende
ncies: {exc}")

    def get_system_info(self)
:
        """
        This method logs the sy
stem information, including the operating sys
tem, CPU archutecture and Python version
    
    """
        os_name = platform.system()
 
       os_version = platform.version()
      
  self.logger.info(f"Operating System: {os_na
me} {os_version}")

        system_info = pla
tform.uname()
        self.logger.info(f"Syst
em: {system_info.system} Node: {system_info.n
ode} Release: {system_info.release} Machine: 
{system_info.machine} Proc: {system_info.proc
essor}")

        python_version = platform.p
ython_version()
        self.logger.info(f"Py
thon Version: {python_version}")

        pyt
orch_version = torch.__version__
        self
.logger.info(f"PyTorch Version: {pytorch_vers
ion}")
        return system_info

    def ch
eck_ffmpeg_installed(self):
        """
     
   This method checks if ffmpeg is installed 
and logs its version.
        """
        try
:
            ffmpeg_version_output = subproc
ess.check_output(["ffmpeg", "-version"], text
=True)
            first_line = ffmpeg_versio
n_output.splitlines()[0]
            self.log
ger.info(f"FFmpeg installed: {first_line}")
 
       except FileNotFoundError:
            
self.logger.error("FFmpeg is not installed. P
lease install FFmpeg to use this package.")
 
           # Raise an exception if this is be
ing run by a user, as ffmpeg is required for 
pydub to write audio
            # but if we'
re just running unit tests in CI, no reason t
o throw
            if "PYTEST_CURRENT_TEST" 
not in os.environ:
                raise

   
 def log_onnxruntime_packages(self):
        
"""
        This method logs the ONNX Runtime
 package versions, including the GPU and Sili
con packages if available.
        """
      
  onnxruntime_gpu_package = self.get_package_
distribution("onnxruntime-gpu")
        onnxr
untime_silicon_package = self.get_package_dis
tribution("onnxruntime-silicon")
        onnx
runtime_cpu_package = self.get_package_distri
bution("onnxruntime")
        onnxruntime_dml
_package = self.get_package_distribution("onn
xruntime-directml")

        if onnxruntime_g
pu_package is not None:
            self.logg
er.info(f"ONNX Runtime GPU package installed 
with version: {onnxruntime_gpu_package.versio
n}")
        if onnxruntime_silicon_package i
s not None:
            self.logger.info(f"ON
NX Runtime Silicon package installed with ver
sion: {onnxruntime_silicon_package.version}")

        if onnxruntime_cpu_package is not No
ne:
            self.logger.info(f"ONNX Runti
me CPU package installed with version: {onnxr
untime_cpu_package.version}")
        if onnx
runtime_dml_package is not None:
            
self.logger.info(f"ONNX Runtime DirectML pack
age installed with version: {onnxruntime_dml_
package.version}")

    def setup_torch_devic
e(self, system_info):
        """
        Thi
s method sets up the PyTorch and/or ONNX Runt
ime inferencing device, using GPU hardware ac
celeration if available.
        """
        
hardware_acceleration_enabled = False
       
 ort_providers = ort.get_available_providers(
)
        has_torch_dml_installed = self.get_
package_distribution("torch_directml")

     
   self.torch_device_cpu = torch.device("cpu"
)

        if torch.cuda.is_available():
    
        self.configure_cuda(ort_providers)
  
          hardware_acceleration_enabled = Tru
e
        elif hasattr(torch.backends, "mps")
 and torch.backends.mps.is_available() and sy
stem_info.processor == "arm":
            sel
f.configure_mps(ort_providers)
            ha
rdware_acceleration_enabled = True
        el
if self.use_directml and has_torch_dml_instal
led:
            import torch_directml
      
      if torch_directml.is_available():
     
           self.configure_dml(ort_providers)

                hardware_acceleration_enabled
 = True

        if not hardware_acceleration
_enabled:
            self.logger.info("No ha
rdware acceleration could be configured, runn
ing in CPU mode")
            self.torch_devi
ce = self.torch_device_cpu
            self.o
nnx_execution_provider = ["CPUExecutionProvid
er"]

            # Discoverability hint: Dir
ectML is an explicit opt-in (experimental). I
f the
            # DirectML packages are ins
talled but the feature wasn't enabled, tell t
he user how.
            if not self.use_dire
ctml and (has_torch_dml_installed or self.get
_package_distribution("onnxruntime-directml")
 is not None):
                self.logger.in
fo(
                    "DirectML packages de
tected but DirectML is not enabled. "
       
             "Pass use_directml=True (or --us
e_directml on the CLI) to enable experimental
 DirectML acceleration."
                )

 
   def configure_cuda(self, ort_providers):
 
       """
        This method configures the
 CUDA device for PyTorch and ONNX Runtime, if
 available.
        """
        self.logger.i
nfo("CUDA is available in Torch, setting Torc
h device to CUDA")
        self.torch_device 
= torch.device("cuda")
        if "CUDAExecut
ionProvider" in ort_providers:
            se
lf.logger.info("ONNXruntime has CUDAExecution
Provider available, enabling acceleration")
 
           self.onnx_execution_provider = ["C
UDAExecutionProvider"]
        else:
        
    self.logger.warning("CUDAExecutionProvide
r not available in ONNXruntime, so accelerati
on will NOT be enabled")

    def configure_m
ps(self, ort_providers):
        """
        
This method configures the Apple Silicon MPS/
CoreML device for PyTorch and ONNX Runtime, i
f available.
        """
        self.logger.
info("Apple Silicon MPS/CoreML is available i
n Torch and processor is ARM, setting Torch d
evice to MPS")
        self.torch_device_mps 
= torch.device("mps")

        self.torch_dev
ice = self.torch_device_mps

        if "Core
MLExecutionProvider" in ort_providers:
      
      self.logger.info("ONNXruntime has CoreM
LExecutionProvider available, enabling accele
ration")
            self.onnx_execution_prov
ider = ["CoreMLExecutionProvider"]
        el
se:
            self.logger.warning("CoreMLEx
ecutionProvider not available in ONNXruntime,
 so acceleration will NOT be enabled")

    d
ef configure_dml(self, ort_providers):
      
  """
        This method configures the Dire
ctML device for PyTorch and ONNX Runtime, if 
available.
        """
        import torch_d
irectml
        self.logger.info("DirectML is
 available in Torch, setting Torch device to 
DirectML")
        self.torch_device_dml = to
rch_directml.device() 
        self.torch_dev
ice = self.torch_device_dml

        if "DmlE
xecutionProvider" in ort_providers:
         
   self.logger.info("ONNXruntime has DmlExecu
tionProvider available, enabling acceleration
")
            self.onnx_execution_provider =
 ["DmlExecutionProvider"]
        else:
     
       self.logger.warning("DmlExecutionProvi
der not available in ONNXruntime, so accelera
tion will NOT be enabled")

    def get_packa
ge_distribution(self, package_name):
        
"""
        This method returns the package d
istribution for a given package name if insta
lled, or None otherwise.
        """
        
try:
            return metadata.distribution
(package_name)
        except metadata.Packag
eNotFoundError:
            self.logger.debug
(f"Python package: {package_name} not install
ed")
            return None

    def get_mod
el_hash(self, model_path):
        """
      
  This method returns the MD5 hash of a given
 model file.
        """
        self.logger.
debug(f"Calculating hash of model file {model
_path}")
        # Use the specific byte coun
t from the original logic
        BYTES_TO_HA
SH = 10000 * 1024  # 10,240,000 bytes

      
  try:
            file_size = os.path.getsiz
e(model_path)

            with open(model_pa
th, "rb") as f:
                if file_size 
< BYTES_TO_HASH:
                    # Hash t
he entire file if smaller than the target byt
e count
                    self.logger.debug
(f"File size {file_size} < {BYTES_TO_HASH}, h
ashing entire file.")
                    has
h_value = hashlib.md5(f.read()).hexdigest()
 
               else:
                    # Se
ek to the specific position before the end (f
rom the beginning) and hash
                 
   seek_pos = file_size - BYTES_TO_HASH
     
               self.logger.debug(f"File size 
{file_size} >= {BYTES_TO_HASH}, seeking to {s
eek_pos} and hashing remaining bytes.")
     
               f.seek(seek_pos, io.SEEK_SET)

                    hash_value = hashlib.md5(
f.read()).hexdigest()

            # Log the 
calculated hash
            self.logger.info(
f"Hash of model file {model_path} is {hash_va
lue}")
            return hash_value

       
 except FileNotFoundError:
            self.l
ogger.error(f"Model file not found at {model_
path}")
            raise # Re-raise the spec
ific error
        except Exception as e:
   
         # Catch other potential errors (e.g.
, permissions, other IOErrors)
            se
lf.logger.error(f"Error calculating hash for 
{model_path}: {e}")
            raise # Re-ra
ise other errors

    def download_file_if_no
t_exists(self, url, output_path):
        """

        This method downloads a file from a 
given URL to a given output path, if the file
 does not already exist.
        """

       
 if os.path.isfile(output_path):
            
self.logger.debug(f"File already exists at {o
utput_path}, skipping download")
            
return

        self.logger.debug(f"Downloadi
ng file from {url} to {output_path} with time
out 300s")
        response = requests.get(ur
l, stream=True, timeout=300)

        if resp
onse.status_code == 200:
            total_si
ze_in_bytes = int(response.headers.get("conte
nt-length", 0))
            progress_bar = tq
dm(total=total_size_in_bytes, unit="iB", unit
_scale=True)

            with open(output_pa
th, "wb") as f:
                for chunk in 
response.iter_content(chunk_size=8192):
     
               progress_bar.update(len(chunk)
)
                    f.write(chunk)
        
    progress_bar.close()
        else:
      
      raise RuntimeError(f"Failed to download
 file from {url}, response code: {response.st
atus_code}")

    def list_supported_model_fi
les(self):
        """
        This method li
sts the supported model files for audio-separ
ator, by fetching the same file UVR uses to l
ist these.
        Also includes model perfor
mance scores where available.

        Exampl
e response object:

        {
            "MD
X": {
                "MDX-Net Model VIP: UVR
-MDX-NET-Inst_full_292": {
                "f
ilename": "UVR-MDX-NET-Inst_full_292.onnx",
 
               "scores": {
                  
  "vocals": {
                    "SDR": 10.6
497,
                    "SIR": 20.3786,
    
                "SAR": 10.692,
              
      "ISR": 14.848
                    },
  
                  "instrumental": {
         
           "SDR": 15.2149,
                  
  "SIR": 25.6075,
                    "SAR": 
17.1363,
                    "ISR": 17.7893
 
                   }
                },
     
           "download_files": [
              
      "UVR-MDX-NET-Inst_full_292.onnx"
      
          ]
                }
            },

            "Demucs": {
                "Demu
cs v4: htdemucs_ft": {
                "filen
ame": "htdemucs_ft.yaml",
                "sc
ores": {
                    "vocals": {
    
                "SDR": 11.2685,
             
       "SIR": 21.257,
                    "SA
R": 11.0359,
                    "ISR": 19.37
53
                    },
                   
 "drums": {
                    "SDR": 13.235
,
                    "SIR": 23.3053,
       
             "SAR": 13.0313,
                
    "ISR": 17.2889
                    },
   
                 "bass": {
                  
  "SDR": 9.72743,
                    "SIR": 
19.5435,
                    "SAR": 9.20801,

                    "ISR": 13.5037
          
          }
                },
              
  "download_files": [
                    "ht
tps://dl.fbaipublicfiles.com/demucs/hybrid_tr
ansformer/f7e0c4bc-ba3fe64a.th",
            
        "https://dl.fbaipublicfiles.com/demuc
s/hybrid_transformer/d12395a8-e57c48e6.th",
 
                   "https://dl.fbaipublicfile
s.com/demucs/hybrid_transformer/92cfc3b6-ef3b
cb9c.th",
                    "https://dl.fba
ipublicfiles.com/demucs/hybrid_transformer/04
573f0d-f3cf25b2.th",
                    "htt
ps://github.com/TRvlvr/model_repo/releases/do
wnload/all_public_uvr_models/htdemucs_ft.yaml
"
                ]
                }
       
     },
            "MDXC": {
               
 "MDX23C Model: MDX23C-InstVoc HQ": {
       
         "filename": "MDX23C-8KFFT-InstVoc_HQ
.ckpt",
                "scores": {
         
           "vocals": {
                    "S
DR": 11.9504,
                    "SIR": 23.1
166,
                    "SAR": 12.093,
     
               "ISR": 15.4782
               
     },
                    "instrumental": {

                    "SDR": 16.3035,
        
            "SIR": 26.6161,
                 
   "SAR": 18.5167,
                    "ISR":
 18.3939
                    }
              
  },
                "download_files": [
    
                "MDX23C-8KFFT-InstVoc_HQ.ckpt
",
                    "model_2_stem_full_ban
d_8k.yaml"
                ]
                
}
            }
        }
        """
       
 download_checks_path = os.path.join(self.mod
el_file_dir, "download_checks.json")

       
 self.download_file_if_not_exists("https://ra
w.githubusercontent.com/TRvlvr/application_da
ta/main/filelists/download_checks.json", down
load_checks_path)

        model_downloads_li
st = json.load(open(download_checks_path, enc
oding="utf-8"))
        self.logger.debug(f"U
VR model download list loaded")

        # Lo
ad the model scores with error handling
     
   model_scores = {}
        try:
           
 with resources.open_text("audio_separator", 
"models-scores.json") as f:
                m
odel_scores = json.load(f)
            self.l
ogger.debug(f"Model scores loaded")
        e
xcept json.JSONDecodeError as e:
            
self.logger.warning(f"Failed to load model sc
ores: {str(e)}")
            self.logger.warn
ing("Continuing without model scores")

     
   # Only show Demucs v4 models as we've only
 implemented support for v4
        filtered_
demucs_v4 = {key: value for key, value in mod
el_downloads_list["demucs_download_list"].ite
ms() if key.startswith("Demucs v4")}

       
 # Modified Demucs handling to use YAML files
 as identifiers and include download files
  
      demucs_models = {}
        for name, fi
les in filtered_demucs_v4.items():
          
  # Find the YAML file in the model files
   
         yaml_file = next((filename for filen
ame in files.keys() if filename.endswith(".ya
ml")), None)
            if yaml_file:
      
          model_score_data = model_scores.get
(yaml_file, {})
                demucs_models
[name] = {
                    "filename": ya
ml_file,
                    "scores": model_
score_data.get("median_scores", {}),
        
            "stems": model_score_data.get("st
ems", []),
                    "target_stem":
 model_score_data.get("target_stem"),
       
             "download_files": list(files.val
ues()),  # List of all download URLs/filename
s
                }

        # Load the JSON 
file using importlib.resources
        with r
esources.open_text("audio_separator", "models
.json") as f:
            audio_separator_mod
els_list = json.load(f)
        self.logger.d
ebug(f"Audio-Separator model list loaded")

 
       # Return object with list of model nam
es
        model_files_grouped_by_type = {
  
          "VR": {
                name: {
   
                 "filename": filename,
      
              "scores": model_scores.get(file
name, {}).get("median_scores", {}),
         
           "stems": model_scores.get(filename
, {}).get("stems", []),
                    "
target_stem": model_scores.get(filename, {}).
get("target_stem"),
                    "down
load_files": [filename],
                }  #
 Just the filename for VR models
            
    for name, filename in {**model_downloads_
list["vr_download_list"], **audio_separator_m
odels_list["vr_download_list"]}.items()
     
       },
            "MDX": {
              
  name: {
                    "filename": fil
ename,
                    "scores": model_sc
ores.get(filename, {}).get("median_scores", {
}),
                    "stems": model_scores
.get(filename, {}).get("stems", []),
        
            "target_stem": model_scores.get(f
ilename, {}).get("target_stem"),
            
        "download_files": [filename],
       
         }  # Just the filename for MDX model
s
                for name, filename in {**mo
del_downloads_list["mdx_download_list"], **mo
del_downloads_list["mdx_download_vip_list"], 
**audio_separator_models_list["mdx_download_l
ist"]}.items()
            },
            "De
mucs": demucs_models,
            "MDXC": {
 
               name: {
                    "f
ilename": next(iter(files.keys())),
         
           "scores": model_scores.get(next(it
er(files.keys())), {}).get("median_scores", {
}),
                    "stems": model_scores
.get(next(iter(files.keys())), {}).get("stems
", []),
                    "target_stem": mo
del_scores.get(next(iter(files.keys())), {}).
get("target_stem"),
                    "down
load_files": list(files.keys()) + list(files.
values()),  # List of both model filenames an
d config filenames
                }
        
        for name, files in {
                
    **model_downloads_list["mdx23c_download_l
ist"],
                    **model_downloads_
list["mdx23c_download_vip_list"],
           
         **model_downloads_list["roformer_dow
nload_list"],
                    **audio_sep
arator_models_list["mdx23c_download_list"],
 
                   **audio_separator_models_l
ist["roformer_download_list"],
              
  }.items()
            },
        }

       
 return model_files_grouped_by_type

    def 
print_uvr_vip_message(self):
        """
    
    This method prints a message to the user 
if they have downloaded a VIP model, remindin
g them to support Anjok07 on Patreon.
       
 """
        if self.model_is_uvr_vip:
      
      self.logger.warning(f"The model: '{self
.model_friendly_name}' is a VIP model, intend
ed by Anjok07 for access by paying subscriber
s only.")
            self.logger.warning("If
 you are not already subscribed, please consi
der supporting the developer of UVR, Anjok07 
by subscribing here: https://patreon.com/uvr"
)

    def download_model_files(self, model_f
ilename):
        """
        This method dow
nloads the model files for a given model file
name, if they are not already present.
      
  Returns tuple of (model_filename, model_typ
e, model_friendly_name, model_path, yaml_conf
ig_filename)
        """
        model_path =
 os.path.join(self.model_file_dir, f"{model_f
ilename}")

        supported_model_files_gro
uped = self.list_supported_model_files()
    
    public_model_repo_url_prefix = "https://g
ithub.com/TRvlvr/model_repo/releases/download
/all_public_uvr_models"
        vip_model_rep
o_url_prefix = "https://github.com/Anjok0109/
ai_magic/releases/download/v5"
        audio_
separator_models_repo_url_prefix = "https://g
ithub.com/nomadkaraoke/python-audio-separator
/releases/download/model-configs"

        ya
ml_config_filename = None

        self.logge
r.debug(f"Searching for model_filename {model
_filename} in supported_model_files_grouped")


        # Iterate through model types (MDX,
 Demucs, MDXC)
        for model_type, models
 in supported_model_files_grouped.items():
  
          # Iterate through each model in thi
s type
            for model_friendly_name, m
odel_info in models.items():
                
self.model_is_uvr_vip = "VIP" in model_friend
ly_name
                model_repo_url_prefix
 = vip_model_repo_url_prefix if self.model_is
_uvr_vip else public_model_repo_url_prefix

 
               # Check if this model matches 
our target filename
                if model_
info["filename"] == model_filename or model_f
ilename in model_info["download_files"]:
    
                self.logger.debug(f"Found mat
ching model: {model_friendly_name}")
        
            self.model_friendly_name = model_
friendly_name
                    self.print_
uvr_vip_message()

                    # Down
load each required file for this model
      
              for file_to_download in model_i
nfo["download_files"]:
                      
  # For URLs, extract just the filename porti
on
                        if file_to_downloa
d.startswith("http"):
                       
     filename = file_to_download.split("/")[-
1]
                            download_path 
= os.path.join(self.model_file_dir, filename)

                            self.download_fi
le_if_not_exists(file_to_download, download_p
ath)
                            continue

  
                      download_path = os.path
.join(self.model_file_dir, file_to_download)


                        # For MDXC models, h
andle YAML config files specially
           
             if model_type == "MDXC" and file
_to_download.endswith(".yaml"):
             
               yaml_config_filename = file_to
_download
                            try:
  
                              yaml_url = f"{m
odel_repo_url_prefix}/mdx_model_data/mdx_c_co
nfigs/{file_to_download}"
                   
             self.download_file_if_not_exists
(yaml_url, download_path)
                   
         except RuntimeError:
               
                 self.logger.debug("YAML conf
ig not found in UVR repo, trying audio-separa
tor models repo...")
                        
        yaml_url = f"{audio_separator_models_
repo_url_prefix}/{file_to_download}"
        
                        self.download_file_if
_not_exists(yaml_url, download_path)
        
                    continue

               
         # For regular model files, try UVR r
epo first, then audio-separator repo
        
                try:
                        
    download_url = f"{model_repo_url_prefix}/
{file_to_download}"
                         
   self.download_file_if_not_exists(download_
url, download_path)
                        e
xcept RuntimeError:
                         
   self.logger.debug("Model not found in UVR 
repo, trying audio-separator models repo...")

                            download_url = f
"{audio_separator_models_repo_url_prefix}/{fi
le_to_download}"
                            
self.download_file_if_not_exists(download_url
, download_path)

                    return 
model_filename, model_type, model_friendly_na
me, model_path, yaml_config_filename

       
 raise ValueError(f"Model file {model_filenam
e} not found in supported model files")

    
def load_model_data_from_yaml(self, yaml_conf
ig_filename):
        """
        This method
 loads model-specific parameters from the YAM
L file for that model.
        The parameters
 in the YAML are critical to inferencing, as 
they need to match whatever was used during t
raining.
        """
        # Verify if the 
YAML filename includes a full path or just th
e filename
        if not os.path.exists(yaml
_config_filename):
            model_data_yam
l_filepath = os.path.join(self.model_file_dir
, yaml_config_filename)
        else:
       
     model_data_yaml_filepath = yaml_config_f
ilename

        self.logger.debug(f"Loading 
model data from YAML at path {model_data_yaml
_filepath}")

        model_data = yaml.load(
open(model_data_yaml_filepath, encoding="utf-
8"), Loader=yaml.FullLoader)
        self.log
ger.debug(f"Model data loaded from YAML file:
 {model_data}")

        from .roformer.confi
guration_normalizer import ConfigurationNorma
lizer

        configured_roformer_type = Con
figurationNormalizer().detect_model_type(mode
l_data)
        yaml_filename = os.path.basen
ame(model_data_yaml_filepath).lower()
       
 if configured_roformer_type is not None or "
roformer" in yaml_filename:
            model
_data["is_roformer"] = True

        return m
odel_data

    def load_model_data_using_hash
(self, model_path):
        """
        This 
method loads model-specific parameters from U
VR model data files.
        These parameters
 are critical to inferencing using a given mo
del, as they need to match whatever was used 
during training.
        The correct paramete
rs are identified by calculating the hash of 
the model file and looking up the hash in the
 UVR data files.
        """
        # Model 
data and configuration sources from UVR
     
   model_data_url_prefix = "https://raw.githu
busercontent.com/TRvlvr/application_data/main
"

        vr_model_data_url = f"{model_data_
url_prefix}/vr_model_data/model_data_new.json
"
        mdx_model_data_url = f"{model_data_
url_prefix}/mdx_model_data/model_data_new.jso
n"

        # Calculate hash for the download
ed model
        self.logger.debug("Calculati
ng MD5 hash for model file to identify model 
parameters from UVR data...")
        model_h
ash = self.get_model_hash(model_path)
       
 self.logger.debug(f"Model {model_path} has h
ash {model_hash}")

        # Setting up the 
path for model data and checking its existenc
e
        vr_model_data_path = os.path.join(s
elf.model_file_dir, "vr_model_data.json")
   
     self.logger.debug(f"VR model data path s
et to {vr_model_data_path}")
        self.dow
nload_file_if_not_exists(vr_model_data_url, v
r_model_data_path)

        mdx_model_data_pa
th = os.path.join(self.model_file_dir, "mdx_m
odel_data.json")
        self.logger.debug(f"
MDX model data path set to {mdx_model_data_pa
th}")
        self.download_file_if_not_exist
s(mdx_model_data_url, mdx_model_data_path)

 
       # Loading model data from UVR
        
self.logger.debug("Loading MDX and VR model p
arameters from UVR model data files...")
    
    vr_model_data_object = json.load(open(vr_
model_data_path, encoding="utf-8"))
        m
dx_model_data_object = json.load(open(mdx_mod
el_data_path, encoding="utf-8"))

        # L
oad additional model data from audio-separato
r
        self.logger.debug("Loading addition
al model parameters from audio-separator mode
l data file...")
        with resources.open_
text("audio_separator", "model-data.json") as
 f:
            audio_separator_model_data = 
json.load(f)

        # Merge the model data 
objects, with audio-separator data taking pre
cedence
        vr_model_data_object = {**vr_
model_data_object, **audio_separator_model_da
ta.get("vr_model_data", {})}
        mdx_mode
l_data_object = {**mdx_model_data_object, **a
udio_separator_model_data.get("mdx_model_data
", {})}

        if model_hash in mdx_model_d
ata_object:
            model_data = mdx_mode
l_data_object[model_hash]
        elif model_
hash in vr_model_data_object:
            mod
el_data = vr_model_data_object[model_hash]
  
      else:
            raise ValueError(f"Un
supported Model File: parameters for MD5 hash
 {model_hash} could not be found in UVR model
 data file for MDX or VR arch.")

        sel
f.logger.debug(f"Model data loaded using hash
 {model_hash}: {model_data}")

        return
 model_data

    def load_model(self, model_f
ilename="model_bs_roformer_ep_317_sdr_12.9755
.ckpt", force_reload=False):
        """
    
    This method instantiates the architecture
-specific separation class,
        loading t
he separation model into memory, downloading 
it first if necessary.

        Consecutive c
alls with the same single model reuse the loa
ded instance. Set
        ``force_reload`` to
 ``True`` after changing settings captured at
 load time.

        Args:
            model_
filename (str or list): The model filename, o
r filenames for an ensemble.
            forc
e_reload (bool): Reload a matching single mod
el instead of reusing it.
        """
       
 # If an ensemble preset was loaded and no ex
plicit model list was provided, use preset mo
dels
        if self._ensemble_preset_models 
is not None and model_filename == "model_bs_r
oformer_ep_317_sdr_12.9755.ckpt":
           
 model_filename = self._ensemble_preset_model
s

        if isinstance(model_filename, list
):
            if len(model_filename) > 1:
  
              self.model_filename = list(mode
l_filename)
                self.model_filena
mes = list(model_filename)
                se
lf.logger.info(f"Multiple models specified fo
r ensembling: {self.model_filenames}")
      
          return
            model_filename =
 model_filename[0]

        if not force_relo
ad and self.model_instance is not None and se
lf._loaded_model_filename == model_filename:

            self.model_filename = model_filen
ame
            self.model_filenames = [model
_filename]
            self.model_friendly_na
me = self._loaded_model_friendly_name
       
     self.model_is_uvr_vip = self._loaded_mod
el_is_uvr_vip
            self.logger.info(f"
Model {model_filename} is already loaded; reu
sing the existing instance.")
            ret
urn

        self.logger.info(f"Loading model
 {model_filename}...")

        load_model_st
art_time = time.perf_counter()
        select
ed_model_filename = model_filename
        pr
evious_model_friendly_name = self.model_frien
dly_name
        previous_model_is_uvr_vip = 
self.model_is_uvr_vip

        try:
         
   # Setting up the model path
            mo
del_filename, model_type, model_friendly_name
, model_path, yaml_config_filename = self.dow
nload_model_files(model_filename)
           
 model_is_uvr_vip = self.model_is_uvr_vip
   
         model_name = model_filename.split(".
")[0]
            self.logger.debug(f"Model d
ownloaded, friendly name: {model_friendly_nam
e}, model_path: {model_path}")

            i
f model_path.lower().endswith(".yaml"):
     
           yaml_config_filename = model_path


            if yaml_config_filename is not N
one:
                model_data = self.load_m
odel_data_from_yaml(yaml_config_filename)
   
         else:
                model_data = s
elf.load_model_data_using_hash(model_path)

 
           common_params = {
                
"logger": self.logger,
                "log_l
evel": self.log_level,
                "torch
_device": self.torch_device,
                
"torch_device_cpu": self.torch_device_cpu,
  
              "torch_device_mps": self.torch_
device_mps,
                "onnx_execution_p
rovider": self.onnx_execution_provider,
     
           "model_name": model_name,
        
        "model_path": model_path,
           
     "model_data": model_data,
              
  "output_format": self.output_format,
      
          "output_bitrate": self.output_bitra
te,
                "output_dir": self.output
_dir,
                "normalization_threshol
d": self.normalization_threshold,
           
     "amplification_threshold": self.amplific
ation_threshold,
                "output_sing
le_stem": self.output_single_stem,
          
      "invert_using_spec": self.invert_using_
spec,
                "sample_rate": self.sam
ple_rate,
                "use_soundfile": se
lf.use_soundfile,
                "use_autoca
st": self.use_autocast,
                "use_
native_fp16": self.use_native_fp16,
         
       "use_torch_compile": self.use_torch_co
mpile,
            }

            # Instantia
te the appropriate separator class depending 
on the model type
            separator_class
es = {"MDX": "mdx_separator.MDXSeparator", "V
R": "vr_separator.VRSeparator", "Demucs": "de
mucs_separator.DemucsSeparator", "MDXC": "mdx
c_separator.MDXCSeparator"}

            if m
odel_type not in self.arch_specific_params or
 model_type not in separator_classes:
       
         # Enhanced error message for Roforme
r models
                if "roformer" in mod
el_filename.lower() or (model_data and model_
data.get("is_roformer", False)):
            
        error_msg = (f"Roformer model type no
t properly configured: {model_type}. "
      
                         f"This may indicate 
a configuration validation failure. "
       
                        f"Please check the mo
del file and YAML configuration.")
          
          self.logger.error(error_msg)
      
              raise ValueError(error_msg)
   
             else:
                    raise 
ValueError(f"Model type not supported (yet): 
{model_type}")

            if model_type == 
"Demucs" and sys.version_info < (3, 10):
    
            raise Exception("Demucs models re
quire Python version 3.10 or newer.")

      
      self.logger.debug(f"Importing module fo
r model type {model_type}: {separator_classes
[model_type]}")

            module_name, cla
ss_name = separator_classes[model_type].split
(".")
            module = importlib.import_m
odule(f"audio_separator.separator.architectur
es.{module_name}")
            separator_clas
s = getattr(module, class_name)

            
self.logger.debug(f"Instantiating separator c
lass for model type {model_type}: {separator_
class}")

            try:
                mo
del_instance = separator_class(common_config=
common_params, arch_config=self.arch_specific
_params[model_type])
            except Excep
tion as e:
                # Enhanced error h
andling for Roformer models
                i
f "roformer" in model_filename.lower() or (mo
del_data and model_data.get("is_roformer", Fa
lse)):
                    error_msg = (f"Fai
led to instantiate Roformer model: {e}. "
   
                            f"This may be due
 to missing parameters or configuration valid
ation failures.")
                    self.lo
gger.error(error_msg)
                    rai
se RuntimeError(error_msg) from e
           
     else:
                    raise

       
     resolve_policy = getattr(model_instance,
 "resolve_execution_policy", None)
          
  if callable(resolve_policy) and not getattr
(model_instance, "_execution_policy_resolved"
, False):
                resolve_policy(mode
l_type.lower())

            # Log Roformer i
mplementation version if applicable
         
   if hasattr(model_instance, 'is_roformer_mo
del') and model_instance.is_roformer_model:
 
               roformer_stats = model_instanc
e.get_roformer_loading_stats()
              
  if roformer_stats:
                    self
.logger.info(f"Roformer loading stats: {rofor
mer_stats}")
        except BaseException:
  
          # Model discovery updates these fie
lds; restore them with the prior
            
# selection so callers never observe a partia
lly loaded model.
            self.model_frie
ndly_name = previous_model_friendly_name
    
        self.model_is_uvr_vip = previous_mode
l_is_uvr_vip
            raise

        self.
model_instance = model_instance
        self.
model_filename = selected_model_filename
    
    self.model_filenames = [selected_model_fi
lename]
        self._loaded_model_filename =
 model_filename
        self.model_friendly_n
ame = model_friendly_name
        self.model_
is_uvr_vip = model_is_uvr_vip
        self._l
oaded_model_friendly_name = model_friendly_na
me
        self._loaded_model_is_uvr_vip = mo
del_is_uvr_vip

        # Log the completion 
of the model load process
        self.logger
.debug("Loading model completed.")
        se
lf.logger.info(f'Load model duration: {time.s
trftime("%H:%M:%S", time.gmtime(int(time.perf
_counter() - load_model_start_time)))}')

   
 def separate(self, audio_file_path, custom_o
utput_names=None):
        """
        Separa
tes the audio file(s) into different stems (e
.g., vocals, instruments) using the loaded mo
del.

        This method takes the path to a
n audio file or a directory containing audio 
files, processes them through
        the loa
ded separation model, and returns the paths t
o the output files containing the separated a
udio stems.
        It handles the entire flo
w from loading the audio, running the separat
ion, clearing up resources, and logging the p
rocess.

        Parameters:
        - audio_
file_path (str or list): The path to the audi
o file or directory, or a list of paths.
    
    - custom_output_names (dict, optional): C
ustom names for the output files. Defaults to
 None.

        Returns:
        - output_fil
es (list of str): Fully written output paths 
produced by inputs that completed
          t
heir full stem set. Valid silent and near-sil
ent stems are included.

        Raises:
    
    - InvalidAudioDataError: Decoded or gener
ated audio data was empty, non-finite, or str
ucturally invalid.
        - AudioExportError
: An audio backend or filesystem failed to pu
blish an output file.
        - BatchSeparati
onError: A list or directory input had one or
 more failures. All discoverable
          in
puts are attempted first; inspect ``successfu
l_files`` and the ordered ``failures`` list.


        A single file fails immediately with
 its original exception. Import the public ex
ception
        types from ``audio_separator.
separator``.
        """
        # Check if t
he model and device are properly initialized

        if not (self.torch_device and (self.m
odel_instance or (isinstance(self.model_filen
ame, list) and len(self.model_filename) > 0))
):
            raise ValueError("Initializati
on failed or model not loaded. Please load a 
model before attempting to separate.")

     
   if isinstance(self.model_filename, list) a
nd len(self.model_filename) > 1:
            
if isinstance(audio_file_path, str) and not o
s.path.isdir(audio_file_path):
              
  return self._separate_ensemble(audio_file_p
ath, custom_output_names)

            ensemb
le_inputs = []
            failures = []
    
        input_paths = [audio_file_path] if is
instance(audio_file_path, str) else audio_fil
e_path
            for path in input_paths:
 
               if os.path.isdir(path):
      
              ensemble_inputs.extend(_iter_di
rectory_audio_files(path))
                el
se:
                    ensemble_inputs.appen
d(path)

            output_files = []
      
      for path in ensemble_inputs:
          
      try:
                    output_files.e
xtend(self._separate_ensemble(path, custom_ou
tput_names))
                except Exception
 as e:
                    self.logger.error(
f"Failed to process ensemble file {path}: {e}
", exc_info=True)
                    failure
s.append((path, e))

            if failures:

                raise BatchSeparationError(o
utput_files, failures)
            return out
put_files

        if isinstance(audio_file_p
ath, str) and not os.path.isdir(audio_file_pa
th):
            self.logger.info(f"Processin
g file: {audio_file_path}")
            retur
n self._separate_file(audio_file_path, custom
_output_names)

        # If audio_file_path 
is a string, convert it to a list for uniform
 processing
        if isinstance(audio_file_
path, str):
            audio_file_path = [au
dio_file_path]

        # Initialize a list t
o store paths of all output files
        out
put_files = []
        failures = []

       
 # Process each path in the list
        for 
path in audio_file_path:
            if os.pa
th.isdir(path):
                # If the path
 is a directory, recursively search for all a
udio files
                for full_path in _
iter_directory_audio_files(path):
           
         self.logger.info(f"Processing file: 
{full_path}")
                    try:
      
                  # Perform separation for ea
ch file
                        files_output 
= self._separate_file(full_path, custom_outpu
t_names)
                        output_files
.extend(files_output)
                    exc
ept Exception as e:
                        s
elf.logger.error(f"Failed to process file {fu
ll_path}: {e}", exc_info=True)
              
          failures.append((full_path, e))
   
         else:
                # If the path 
is a file, process it directly
              
  self.logger.info(f"Processing file: {path}"
)
                try:
                    fi
les_output = self._separate_file(path, custom
_output_names)
                    output_fil
es.extend(files_output)
                excep
t Exception as e:
                    self.lo
gger.error(f"Failed to process file {path}: {
e}", exc_info=True)
                    failu
res.append((path, e))

        if failures:
 
           raise BatchSeparationError(output_
files, failures)
        return output_files


    def _separate_file(self, audio_file_path
, custom_output_names=None):
        """
    
    Internal method to handle separation for 
a single audio file.
        This method perf
orms the actual separation process for a sing
le audio file. It logs the start and end of t
he process,
        handles autocast if enabl
ed, and ensures GPU cache is cleared after pr
ocessing.
        Parameters:
        - audio
_file_path (str): The path to the audio file.

        - custom_output_names (dict, optiona
l): Custom names for the output files. Defaul
ts to None.
        Returns:
        - output
_files (list of str): A list containing the p
aths to the separated audio stem files.
     
   """
        # Check if chunking is enabled
 and file is large enough
        if self.chu
nk_duration is not None:
            import l
ibrosa
            duration = librosa.get_dur
ation(path=audio_file_path)

            from
 audio_separator.separator.audio_chunking imp
ort AudioChunker
            chunker = AudioC
hunker(self.chunk_duration, self.logger)

   
         if chunker.should_chunk(duration):
 
               self.logger.info(f"File durati
on {duration:.1f}s exceeds chunk size {self.c
hunk_duration}s, using chunked processing")
 
               return self._process_with_chun
king(audio_file_path, custom_output_names)

 
       # Log the start of the separation proc
ess
        self.logger.info(f"Starting separ
ation process for audio_file_path: {audio_fil
e_path}")
        separate_start_time = time.
perf_counter()

        # Log normalization a
nd amplification thresholds
        self.logg
er.debug(f"Normalization threshold set to {se
lf.normalization_threshold}, waveform will be
 lowered to this max amplitude to avoid clipp
ing.")
        self.logger.debug(f"Amplificat
ion threshold set to {self.amplification_thre
shold}, waveform will be scaled up to this ma
x amplitude if below it.")

        # Run sep
aration using the policy resolved for the loa
ded model's actual
        # inference device
. This matters when an architecture falls bac
k to CPU.
        output_files = None
       
 effective_precision = self.effective_precisi
on
        inference_device = getattr(self.mo
del_instance, "torch_device", self.torch_devi
ce)
        inference_device_type = getattr(i
nference_device, "type", str(inference_device
))
        try:
            if effective_prec
ision == NATIVE_FP16:
                self.lo
gger.debug("Using native float16 inference.")

                output_files = self.model_in
stance.separate(audio_file_path, custom_outpu
t_names)
            elif effective_precision
 == AUTOCAST and inference_device_type != "pr
ivateuseone":
                self.logger.deb
ug("Using autocast inference on %s.", inferen
ce_device_type)
                with autocast
_mode.autocast(inference_device_type):
      
              output_files = self.model_insta
nce.separate(audio_file_path, custom_output_n
ames)
            else:
                self.
logger.debug("Using float32 inference.")
    
            output_files = self.model_instanc
e.separate(audio_file_path, custom_output_nam
es)
        finally:
            # Clear per-
file state even when inference or audio expor
t fails.
            active_error = sys.exc_i
nfo()[1]
            cleanup_errors = []
    
        for cleanup_name in ("clear_gpu_cache
", "clear_file_specific_paths"):
            
    try:
                    getattr(self.mod
el_instance, cleanup_name)()
                
except Exception as cleanup_error:
          
          cleanup_errors.append((cleanup_name
, cleanup_error))
                    self.lo
gger.error(f"Cleanup step {cleanup_name} fail
ed: {cleanup_error}", exc_info=True)

       
     if cleanup_errors:
                if ac
tive_error is not None:
                    i
f hasattr(active_error, "add_note"):
        
                for cleanup_name, cleanup_err
or in cleanup_errors:
                       
     active_error.add_note(f"Cleanup step {cl
eanup_name} failed: {cleanup_error}")
       
         else:
                    cleanup_na
me, cleanup_error = cleanup_errors[0]
       
             if hasattr(cleanup_error, "add_n
ote"):
                        cleanup_error.
add_note(f"Cleanup step failed: {cleanup_name
}")
                    raise cleanup_error


        # Remind the user one more time if th
ey used a VIP model, so the message doesn't g
et lost in the logs
        self.print_uvr_vi
p_message()

        # Log the completion of 
the separation process
        self.logger.de
bug("Separation process completed.")
        
self.logger.info(f'Separation duration: {time
.strftime("%H:%M:%S", time.gmtime(int(time.pe
rf_counter() - separate_start_time)))}')

   
     return output_files

    def _process_wi
th_chunking(self, audio_file_path, custom_out
put_names=None):
        """
        Process 
large file by splitting into chunks.

       
 This method splits a large audio file into s
maller chunks, processes each chunk
        s
eparately, and merges the results back togeth
er. This helps prevent out-of-memory
        
errors when processing very long audio files.


        Parameters:
        - audio_file_pa
th (str): The path to the audio file.
       
 - custom_output_names (dict, optional): Cust
om names for the output files. Defaults to No
ne.

        Returns:
        - output_files 
(list of str): A list containing the paths to
 the separated audio stem files.
        """

        import tempfile
        import shutil

        from audio_separator.separator.audio
_chunking import AudioChunker

        # Crea
te temporary directory for chunks
        tem
p_dir = tempfile.mkdtemp(prefix="audio-separa
tor-chunks-")
        self.logger.debug(f"Cre
ated temporary directory for chunks: {temp_di
r}")

        try:
            # Split audio 
into chunks
            chunker = AudioChunke
r(self.chunk_duration, self.logger)
         
   chunk_paths = chunker.split_audio(audio_fi
le_path, temp_dir)

            # Process eac
h chunk
            processed_chunks_by_stem 
= {}
            stem_names_by_chunk = []

  
          for i, chunk_path in enumerate(chun
k_paths):
                self.logger.info(f"
Processing chunk {i+1}/{len(chunk_paths)}: {c
hunk_path}")

                original_chunk_
duration = self.chunk_duration
              
  original_output_dir = self.output_dir
     
           self.chunk_duration = None
       
         self.output_dir = temp_dir

        
        if self.model_instance:
             
       original_model_output_dir = self.model
_instance.output_dir
                    self
.model_instance.output_dir = temp_dir

      
          try:
                    output_fil
es = self._separate_file(chunk_path)
        
            current_stem_names = set()

     
               # Dynamically group chunks by 
stem name
                    for stem_index,
 stem_path in enumerate(output_files):
      
                  # Extract stem name from fi
lename: "chunk_0000_(Vocals).wav" → "Vocals
"
                        filename = os.path.
basename(stem_path)
                        m
atch = re.search(r'_\(([^)]+)\)', filename)
 
                       if match:
            
                stem_name = match.group(1)
  
                      else:
                 
           # Fallback: use index-based name i
f pattern not found
                         
   stem_name = f"stem_{stem_index}"
         
                   self.logger.warning(f"Coul
d not extract stem name from {filename}, usin
g {stem_name}")

                        if s
tem_name in current_stem_names:
             
               raise InvalidAudioDataError(f"
Chunk {i} produced duplicate stem output: {st
em_name}")
                        current_st
em_names.add(stem_name)
                     
   if stem_name not in processed_chunks_by_st
em:
                            processed_chu
nks_by_stem[stem_name] = []

                
        # Ensure absolute path
              
          abs_path = stem_path if os.path.isa
bs(stem_path) else os.path.join(temp_dir, ste
m_path)
                        processed_chu
nks_by_stem[stem_name].append(abs_path)

    
                if not output_files:
        
                self.logger.warning(f"Chunk {
i+1} produced no output files")
             
       stem_names_by_chunk.append(current_ste
m_names)

                finally:
          
          self.chunk_duration = original_chun
k_duration
                    self.output_di
r = original_output_dir
                    i
f self.model_instance:
                      
  self.model_instance.output_dir = original_m
odel_output_dir

                # Clear GPU 
cache between chunks
                if self.
model_instance:
                    self.mode
l_instance.clear_gpu_cache()

            all
_stem_names = set().union(*stem_names_by_chun
k) if stem_names_by_chunk else set()
        
    if not all_stem_names:
                ra
ise InvalidAudioDataError("Chunked separation
 produced no stems")

            missing_ste
ms = {
                chunk_index: sorted(al
l_stem_names - chunk_stems)
                f
or chunk_index, chunk_stems in enumerate(stem
_names_by_chunk)
                if chunk_ste
ms != all_stem_names
            }
          
  if missing_stems:
                details =
 "; ".join(f"chunk {chunk_index}: {', '.join(
stems)}" for chunk_index, stems in missing_st
ems.items())
                raise InvalidAud
ioDataError(f"Chunked separation is missing s
tem output(s): {details}")

            # Mer
ge chunks for each stem dynamically
         
   base_name = os.path.splitext(os.path.basen
ame(audio_file_path))[0]
            output_f
iles = []

            for stem_name in sorte
d(processed_chunks_by_stem.keys()):
         
       chunk_paths_for_stem = processed_chunk
s_by_stem[stem_name]

                if not 
chunk_paths_for_stem:
                    sel
f.logger.warning(f"No chunks found for stem: 
{stem_name}")
                    continue

 
               # Determine output filename
  
              if custom_output_names and stem
_name in custom_output_names:
               
     output_filename = custom_output_names[st
em_name]
                else:
              
      output_filename = f"{base_name}_({stem_
name})"

                output_path = os.pat
h.join(self.output_dir, f"{output_filename}.{
self.output_format.lower()}")

              
  self.logger.info(f"Merging {len(chunk_paths
_for_stem)} chunks for stem: {stem_name}")
  
              chunker.merge_chunks(chunk_path
s_for_stem, output_path)
                outp
ut_files.append(output_path)

            sel
f.logger.info(f"Chunked processing completed.
 Output files: {output_files}")
            r
eturn output_files

        finally:
        
    # Clean up temporary directory
          
  if os.path.exists(temp_dir):
              
  self.logger.debug(f"Cleaning up temporary d
irectory: {temp_dir}")
                shutil
.rmtree(temp_dir, ignore_errors=True)

    de
f download_model_and_data(self, model_filenam
e):
        """
        Downloads the model f
ile without loading it into memory.
        "
""
        self.logger.info(f"Downloading mod
el {model_filename}...")

        model_filen
ame, model_type, model_friendly_name, model_p
ath, yaml_config_filename = self.download_mod
el_files(model_filename)

        if model_pa
th.lower().endswith(".yaml"):
            yam
l_config_filename = model_path

        if ya
ml_config_filename is not None:
            m
odel_data = self.load_model_data_from_yaml(ya
ml_config_filename)
        else:
           
 model_data = self.load_model_data_using_hash
(model_path)

        model_data_dict_size = 
len(model_data)

        self.logger.info(f"M
odel downloaded, type: {model_type}, friendly
 name: {model_friendly_name}, model_path: {mo
del_path}, model_data: {model_data_dict_size}
 items")

    def get_simplified_model_list(s
elf, filter_sort_by: Optional[str] = None):
 
       """
        Returns a simplified, user
-friendly list of models with their key metri
cs.
        Optionally sorts the list based o
n the specified criteria.

        :param sor
t_by: Criteria to sort by. Can be "name", "fi
lename", or any stem name
        """
       
 model_files = self.list_supported_model_file
s()
        simplified_list = {}

        for
 model_type, models in model_files.items():
 
           for name, data in models.items():

                filename = data["filename"]
 
               scores = data.get("scores") or
 {}
                stems = data.get("stems")
 or []
                target_stem = data.get
("target_stem")

                # Format ste
ms with their SDR scores where available
    
            stems_with_scores = []
          
      stem_sdr_dict = {}

                # P
rocess each stem from the model's stem list
 
               for stem in stems:
           
         stem_scores = scores.get(stem, {})
 
                   # Add asterisk if this is 
the target stem
                    stem_disp
lay = f"{stem}*" if stem == target_stem else 
stem

                    if isinstance(stem_
scores, dict) and "SDR" in stem_scores:
     
                   sdr = round(stem_scores["S
DR"], 1)
                        stems_with_s
cores.append(f"{stem_display} ({sdr})")
     
                   stem_sdr_dict[stem.lower()
] = sdr
                    else:
           
             # Include stem without SDR score

                        stems_with_scores.ap
pend(stem_display)
                        st
em_sdr_dict[stem.lower()] = None

           
     # If no stems listed, mark as Unknown
  
              if not stems_with_scores:
     
               stems_with_scores = ["Unknown"
]
                    stem_sdr_dict["unknown"
] = None

                simplified_list[fil
ename] = {"Name": name, "Type": model_type, "
Stems": stems_with_scores, "SDR": stem_sdr_di
ct}

        # Sort and filter the list if a 
sort_by parameter is provided
        if filt
er_sort_by:
            if filter_sort_by == 
"name":
                return dict(sorted(si
mplified_list.items(), key=lambda x: x[1]["Na
me"]))
            elif filter_sort_by == "fi
lename":
                return dict(sorted(s
implified_list.items()))
            else:
  
              # Convert sort_by to lowercase 
for case-insensitive comparison
             
   sort_by_lower = filter_sort_by.lower()
   
             # Filter out models that don't h
ave the specified stem
                filter
ed_list = {k: v for k, v in simplified_list.i
tems() if sort_by_lower in v["SDR"]}

       
         # Sort by SDR score if available, pu
tting None values last
                def so
rt_key(item):
                    sdr = item[
1]["SDR"][sort_by_lower]
                    
return (0 if sdr is None else 1, sdr if sdr i
s not None else float("-inf"))

             
   return dict(sorted(filtered_list.items(), 
key=sort_key, reverse=True))

        return 
simplified_list

    def _separate_ensemble(s
elf, audio_file_path, custom_output_names=Non
e):
        """
        Internal method to ha
ndle ensembling of multiple models.
        "
""
        import tempfile
        import shu
til

        if isinstance(audio_file_path, s
tr):
            audio_file_path = [audio_fil
e_path]

        output_files = []

        o
riginal_model_filename = self.model_filename

        original_model_filenames = self.model
_filenames

        for path in audio_file_pa
th:
            self.logger.info(f"Ensemble p
rocessing for file: {path}")

            # C
reate temporary directory for intermediate st
ems
            temp_dir = tempfile.mkdtemp(p
refix="audio-separator-ensemble-")
          
  self.logger.debug(f"Created temporary direc
tory for ensemble: {temp_dir}")

            
try:
                # Store paths of interme
diate stems grouped by stem name
            
    # { "Vocals": ["temp_dir/model1_Vocals.wa
v", "temp_dir/model2_Vocals.wav"], ... }
    
            stems_by_type = {}
              
  original_output_dir = self.output_dir

    
            for model_filename in original_mo
del_filenames:
                    self.logge
r.info(f"Processing with model: {model_filena
me}")

                    # Load the model
 
                   self.load_model(model_file
name)

                    # Set temporary ou
tput directory for this model
               
     self.output_dir = temp_dir
             
       if self.model_instance:
              
          self.model_instance.output_dir = te
mp_dir

                    try:
            
            # Perform separation WITHOUT cust
om_output_names for intermediate files.
     
                   # Intermediate stems must 
use the default "base_(StemType)_model.ext" n
aming
                        # so the regex 
below can extract stem types for classificati
on.
                        # custom_output_n
ames is applied later to the final ensembled 
output.
                        model_stems =
 self._separate_file(path, None)

           
             # Extract and normalize stem nam
es from this model's outputs
                
        model_stem_names = []
               
         for stem_path in model_stems:
      
                      filename = os.path.base
name(stem_path)
                            m
atch = re.search(r'_\(([^)]+)\)', filename)
 
                           stem_name = match.
group(1) if match else "Unknown"
            
                model_stem_names.append(stem_
name)

                        # Normalize st
em names with context about how many stems th
is model produced
                        num
_model_stems = len(model_stem_names)
        
                has_vocal_stem = any(
       
                     "vocal" in s.lower() or 
s.lower() in ("vocals",)
                    
        for s in model_stem_names
           
             )

                        for s
tem_path, raw_stem_name in zip(model_stems, m
odel_stem_names):
                           
 lower_name = raw_stem_name.lower()

        
                    if "vocal" in lower_name 
and "lead" not in lower_name and "backing" no
t in lower_name:
                            
    stem_name = "Vocals"
                    
        elif lower_name == "other" and num_mo
del_stems == 2 and has_vocal_stem:
          
                      # For 2-stem models whe
re one stem is vocals, "other" is the instrum
ental
                                stem_na
me = "Instrumental"
                         
       self.logger.debug(f"Mapped 'other' →
 'Instrumental' for 2-stem model (model produ
ced: {model_stem_names})")
                  
          elif lower_name in STEM_NAME_MAP:
 
                               stem_name = ST
EM_NAME_MAP[lower_name]
                     
       else:
                                
stem_name = raw_stem_name.title()

          
                  if stem_name not in stems_b
y_type:
                                stems
_by_type[stem_name] = []

                   
         abs_path = stem_path if os.path.isab
s(stem_path) else os.path.join(temp_dir, stem
_path)
                            stems_by_t
ype[stem_name].append(abs_path)
             
       finally:
                        self.
output_dir = original_output_dir

           
     # Perform ensembling for each stem type

                ensembler = Ensembler(self.lo
gger, self.ensemble_algorithm, self.ensemble_
weights)
                base_name = os.path.
splitext(os.path.basename(path))[0]

        
        for stem_name, stem_paths in stems_by
_type.items():
                    self.logge
r.info(f"Ensembling {len(stem_paths)} stems f
or type: {stem_name}")

                    w
aveforms = []
                    original_ch
annels = None
                    for sp in s
tem_paths:
                        wav, _ = l
ibrosa.load(sp, mono=False, sr=self.sample_ra
te)
                        if wav.ndim == 1:

                            if original_chan
nels is None:
                               
 original_channels = 1
                      
      wav = np.asfortranarray([wav, wav])
   
                     elif original_channels i
s None:
                            original_
channels = wav.shape[0]
                     
   waveforms.append(wav)

                   
 ensembled_wav = ensembler.ensemble(waveforms
)

                    # Restore original cha
nnel count (avoid fake stereo from mono input
)
                    if original_channels ==
 1 and ensembled_wav.shape[0] > 1:
          
              ensembled_wav = ensembled_wav[:
1, :]

                    # Determine output
 filename
                    if custom_outpu
t_names and stem_name in custom_output_names:

                        output_filename = cu
stom_output_names[stem_name]
                
    elif self.ensemble_preset:
              
          output_filename = f"{base_name}_({s
tem_name})_preset_{self.ensemble_preset}"
   
                 else:
                      
  # Build descriptive name from model filenam
es
                        model_slugs = []
 
                       for mf in original_mod
el_filenames:
                            # R
emove extension, then truncate to keep filena
mes reasonable
                            na
me = os.path.splitext(mf)[0]
                
            # Remove common verbose prefixes

                            for prefix in ["m
el_band_roformer_", "melband_roformer_", "bs_
roformer_", "model_bs_roformer_", "UVR-MDX-NE
T-", "UVR_MDXNET_"]:
                        
        if name.startswith(prefix):
         
                           name = name[len(pr
efix):]
                                    b
reak
                            model_slugs.
append(name[:12])
                        slu
gs_str = "_".join(model_slugs)
              
          output_filename = f"{base_name}_({s
tem_name})_custom_ensemble_{slugs_str}"

    
                output_path = f"{output_filen
ame}.{self.output_format.lower()}"

         
           # Use a dummy model instance to wr
ite the audio if necessary,
                 
   # or just use the last model instance we h
ad.
                    # Actually, we can us
e the write_audio method from the last model_
instance
                    if self.model_in
stance:
                        # Ensure the 
model instance has the correct audio_file_pat
h and output_dir
                        self
.model_instance.audio_file_path = path
      
                  self.model_instance.output_
dir = self.output_dir
                       
 self.model_instance.write_audio(output_path,
 ensembled_wav.T)
                        fin
al_output_path = os.path.join(self.output_dir
, output_path)
                        output
_files.append(final_output_path)
            
        else:
                        # Fallb
ack writer if no model instance is available

                        self.logger.warning(f
"No model instance available to write ensembl
ed audio. Using fallback writer for {output_p
ath}")
                        final_output_p
ath = os.path.join(self.output_dir, output_pa
th)

                        import soundfile
 as sf
                        from audio_sep
arator.separator.uvr_lib_v5 import spec_utils


                        ensemble_audio = va
lidate_audio_source(ensembled_wav.T)
        
                ensemble_audio = spec_utils.n
ormalize(
                            wave=en
semble_audio,
                            max
_peak=self.normalization_threshold,
         
                   min_peak=self.amplificatio
n_threshold,
                        )
      
                  try:
                      
      with atomic_output_path(final_output_pa
th, "soundfile") as temp_output_path:
       
                         self.logger.debug(f"
Attempting to write ensembled audio to {final
_output_path}...")
                          
      sf.write(temp_output_path, ensemble_aud
io, self.sample_rate)
                       
 except AudioExportError as requested_format_
error:
                            self.logge
r.error(
                                f"Er
ror writing {self.output_format} format: {req
uested_format_error}. Falling back to WAV."
 
                           )
                
            final_output_path = final_output_
path.rsplit(".", 1)[0] + ".wav"
             
               with atomic_output_path(final_
output_path, "soundfile") as temp_output_path
:
                                sf.write(te
mp_output_path, ensemble_audio, self.sample_r
ate)

                        output_files.ap
pend(final_output_path)

            finally:

                # Restore original model fil
enames state
                self.model_filen
ame = original_model_filename
               
 self.model_filenames = original_model_filena
mes

                # Clear model instance r
eference
                self.model_instance 
= None

                # Clean up temporary 
directory
                if os.path.exists(t
emp_dir):
                    self.logger.deb
ug(f"Cleaning up temporary directory: {temp_d
ir}")
                    shutil.rmtree(temp_
dir, ignore_errors=True)

        return outp
ut_files


